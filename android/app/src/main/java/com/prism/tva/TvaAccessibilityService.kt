package com.prism.tva

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.prism.tva.core.Actions
import com.prism.tva.core.Dbg
import com.prism.tva.core.Snapshot
import com.prism.tva.core.Text
import com.prism.tva.llm.Brain
import com.prism.tva.llm.Fireworks
import com.prism.tva.recipe.Generaliser
import com.prism.tva.recipe.Matcher
import com.prism.tva.recipe.Store
import com.prism.tva.run.Executor
import com.prism.tva.run.Resolver
import com.prism.tva.teach.Recorder
import com.prism.tva.ui.Asker
import com.prism.tva.ui.Hud
import com.prism.tva.ui.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The assistant's eyes and hands: reads other apps' screens and acts in them through Android's
 * Accessibility Service API only (no app-specific APIs, no deep links).
 */
class TvaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: TvaAccessibilityService? = null
            private set
        private const val DEV = "com.prism.tva."
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: Store
    lateinit var hud: Hud
    lateinit var speaker: Speaker
    lateinit var asker: Asker
    lateinit var recorder: Recorder
    lateinit var executor: Executor
    var homePkg: String? = null
        private set

    @Volatile var lastEventAt = 0L
        private set
    @Volatile var lastWindowClass: String? = null
        private set
    /** Last thing the assistant said, for the app screen. */
    @Volatile var lastStatus: String = ""

    override fun onServiceConnected() {
        Dbg.init(this)
        store = Store(this)
        hud = Hud(this)
        speaker = Speaker(this)
        asker = Asker(this)
        recorder = Recorder(this).also { r -> r.onFinished = { rec -> onTeachFinished(rec) } }
        executor = Executor(this)
        homePkg = Actions.homePackage(this)
        getSharedPreferences("tva", MODE_PRIVATE).getString("fw_key", null)?.takeIf { it.isNotBlank() }?.let { Fireworks.key = it }
        val filter = IntentFilter().apply {
            listOf("TEACH_START", "TEACH_STOP", "TEACH_CANCEL", "RUN", "RUN_RECIPE", "STOP", "LIST", "DUMP",
                "DELETE", "EXPLAIN", "ANSWER", "MATCH", "GUARD").forEach { addAction(DEV + it) }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(devCommands, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(devCommands, filter)
        instance = this
        Dbg.log("SERVICE_CONNECTED home=$homePkg recipes=${store.recipes().size} llm=${Fireworks.available}")
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(devCommands) }
        runCatching { recorder.stop(save = false) }
        runCatching { speaker.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: ""
        if (pkg == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> lastEventAt = System.currentTimeMillis()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastEventAt = System.currentTimeMillis()
                val cls = e.className?.toString()
                if (cls != null && cls.contains('.') && !cls.startsWith("android.widget") &&
                    !cls.startsWith("android.view") && !cls.startsWith("android.inputmethodservice")) {
                    lastWindowClass = cls
                }
            }
        }
        if (::recorder.isInitialized && recorder.active) recorder.onEvent(e)
    }

    fun snapshot(): Snapshot = Snapshot.capture(this, packageName, lastWindowClass)

    /** Waits until the screen stops changing for [quietMs] (or [maxMs] passes). */
    suspend fun settle(quietMs: Long = 400, maxMs: Long = 4000) {
        val start = System.currentTimeMillis()
        delay(120)
        while (System.currentTimeMillis() - start < maxMs) {
            if (System.currentTimeMillis() - lastEventAt >= quietMs) return
            delay(80)
        }
    }

    fun say(text: String) {
        lastStatus = text
        speaker.say(text)
    }

    // ------------------------------------------------------------------ teach

    fun startTeaching(command: String) {
        if (executor.running) executor.cancel()
        lastStatus = "Teaching: $command"
        recorder.start(command)
    }

    private fun onTeachFinished(rec: JSONObject?) {
        if (rec == null) return
        scope.launch {
            val recipe = Generaliser.build(rec, this@TvaAccessibilityService, homePkg, packageName)
            if (recipe.optString("app").isEmpty() || recipe.getJSONArray("steps").length() < 2) {
                say("I didn't see any steps in an app. Let's try teaching that again.")
                Dbg.log("TEACH_EMPTY ${rec.optString("id")}")
                return@launch
            }
            store.saveRecipe(recipe)
            hud.show("Learning from your demonstration…")
            // Teach-time LLM pass: slot names, step intents, quantity/address goals, noise.
            withTimeoutOrNull(35000) { Brain.refine(recipe) }?.let { r ->
                runCatching { Brain.applyRefinement(recipe, r) }.onSuccess { store.saveRecipe(it) }
                    .onFailure { Dbg.log("REFINE_APPLY_FAILED $it") }
            }
            val n = recipe.getJSONArray("steps").length()
            val ex = Matcher.examples(recipe)
            var name = recipe.optString("description").ifEmpty { recipe.optString("template") }
            ex.forEach { (k, v) -> name = name.replace("{$k}", v) }
            Dbg.log("LEARNED ${recipe.getString("id")} \"${recipe.optString("description")}\" template=\"${recipe.optString("template")}\" steps=$n\n${recipe.toString(1)}")
            say("Learned: $name. $n steps.")
            hud.show("Learned: ${recipe.optString("description").ifEmpty { recipe.optString("template") }} · $n steps",
                listOf("OK" to { hud.hide() }), autoHideMs = 8000)
            startActivity(Intent(this@TvaAccessibilityService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("recipe", recipe.getString("id")))
        }
    }

    // ------------------------------------------------------------------ commands

    /** What a command means: a status question, a learned flow with values, or nothing learned. */
    class Resolution(val kind: String, val recipe: JSONObject?, val slots: Map<String, String>, val how: String,
                     val otherApp: String?, val missing: List<String>, val ms: Long)

    suspend fun resolveCommand(u: String): Resolution {
        val t0 = System.currentTimeMillis()
        fun done(kind: String, r: JSONObject? = null, s: Map<String, String> = emptyMap(), how: String = "", other: String? = null, missing: List<String> = emptyList()) =
            Resolution(kind, r, s, how, other, missing, System.currentTimeMillis() - t0)
        if (looksLikeStatusQuery(u)) return done("status", how = "keywords")
        val recipes = store.recipes()
        // 1. Same words as a taught command (or its template): no network needed.
        Matcher.match(u, recipes)?.let { m -> return done("run", m.recipe, m.slots, m.how) }
        // 2. Paraphrases, changed values, other apps, missing values: LLM.
        val lm = if (recipes.isNotEmpty() && Fireworks.available) Brain.matchCommand(u, recipes) else null
        if (lm?.statusQuery == true) return done("status", how = "llm")
        val recipe = lm?.flowId?.let { id -> recipes.firstOrNull { it.optString("id") == id } }
        if (recipe == null || lm.confidence < 0.5) return done("none", how = if (lm == null) "no-llm" else "llm")
        val slots = HashMap(lm.slots)
        // Optional goal slots (quantity, address) fall back to what was demonstrated.
        recipe.optJSONArray("slots")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                if (!s.optBoolean("required", true) && !slots.containsKey(s.getString("name"))) slots[s.getString("name")] = s.optString("example")
            }
        }
        val sameApp = lm.otherApp == null ||
            Text.norm(lm.otherApp) == Text.norm(recipe.optString("appLabel")) ||
            Text.fuzzyContains(recipe.optString("appLabel"), lm.otherApp)
        val other = if (sameApp) null else findApp(lm.otherApp!!)?.takeIf { it != recipe.optString("app") }
        return done("run", recipe, slots, "llm conf=${lm.confidence}", other, lm.missing)
    }

    @Volatile private var lastUtterance = ""
    @Volatile private var lastUtteranceAt = 0L

    /** Handles a spoken or typed command: answer a status question, run a learned flow, or offer to learn it. */
    fun handleUtterance(utterance: String) {
        scope.launch {
            val u = utterance.trim()
            if (u.isEmpty()) return@launch
            // Some recognizers deliver the same result twice; a second copy would cancel the first run.
            val now = System.currentTimeMillis()
            if (Text.norm(u) == lastUtterance && now - lastUtteranceAt < 5000) {
                Dbg.log("UTTERANCE duplicate ignored \"$u\"")
                return@launch
            }
            lastUtterance = Text.norm(u); lastUtteranceAt = now
            Dbg.log("UTTERANCE \"$u\"")
            val r = resolveCommand(u)
            when (r.kind) {
                "status" -> answerStatus()
                "run" -> {
                    Dbg.log("MATCH \"$u\" -> ${r.recipe!!.optString("id")} (${r.how}) ${r.slots} missing=${r.missing} app=${r.otherApp ?: "-"}")
                    executor.start(r.recipe, r.slots, u, r.otherApp)
                }
                else -> {
                    Dbg.log("NO_MATCH \"$u\"")
                    say("I haven't learned that yet. Do you want to teach me?")
                    hud.show("Not learned yet: \"$u\"", listOf("Teach it" to { startTeaching(u) }, "No" to { hud.hide() }), autoHideMs = 15000)
                }
            }
        }
    }

    private fun looksLikeStatusQuery(u: String): Boolean {
        val n = Text.norm(u)
        return Regex("\\b(last|previous) (run|order|task|time)\\b").containsMatchIn(n) ||
            Regex("^(did|was) (it|that|the last one) (work|succeed|go through|finish)").containsMatchIn(n)
    }

    private fun answerStatus() {
        val last = store.runs(1).lastOrNull()
        if (last == null) { say("I haven't run anything yet."); return }
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(last.optLong("startedAt")))
        val what = last.optString("recipeName").ifEmpty { last.optString("utterance") }
        val steps = last.optInt("stepCount")
        val msg = when (last.optString("outcome")) {
            "success" -> "Yes. Your last run, $what, at $time, completed all $steps steps."
            "handover" -> "Your last run, $what, at $time, went as far as it safely could and stopped at ${last.optString("reason")} for you to finish."
            "stopped" -> "Your last run, $what, was stopped at step ${last.optInt("stoppedAt") + 1}."
            else -> "No. Your last run, $what, at $time, failed at step ${last.optInt("stoppedAt") + 1} of $steps: ${last.optString("reason")}."
        }
        say(msg)
        hud.show(msg, listOf("OK" to { hud.hide() }), autoHideMs = 10000)
    }

    /** Installed app whose name matches (for running a flow in a similar app). */
    private fun findApp(name: String): String? {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        // Exact name first ("Amazon" must not become "Amazon Alexa"), then a unique close match.
        apps.firstOrNull { (_, label) -> Text.norm(label) == Text.norm(name) }?.let { return it.first }
        val close = apps.filter { (_, label) -> Text.fuzzyContains(label, name) }
        return if (close.size == 1) close[0].first else null
    }

    // ------------------------------------------------------------------ development hooks (adb)

    private val devCommands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action?.removePrefix(DEV)) {
                "TEACH_START" -> startTeaching(intent.getStringExtra("command") ?: "untitled")
                "TEACH_STOP" -> recorder.finish()
                "TEACH_CANCEL" -> recorder.stop(save = false)
                "RUN" -> handleUtterance(intent.getStringExtra("utterance") ?: "")
                "ANSWER" -> asker.answer(intent.getStringExtra("text"))
                "MATCH" -> {
                    val u = intent.getStringExtra("utterance") ?: ""
                    val tag = intent.getStringExtra("tag") ?: ""
                    scope.launch {
                        val r = resolveCommand(u)
                        Dbg.log("MATCHRESULT " + JSONObject().put("tag", tag).put("utterance", u).put("kind", r.kind)
                            .put("recipe", r.recipe?.optString("id") ?: "").put("recipeApp", r.recipe?.optString("appLabel") ?: "")
                            .put("slots", JSONObject(r.slots as Map<*, *>))
                            .put("missing", org.json.JSONArray(r.missing)).put("how", r.how).put("app", r.otherApp ?: "").put("ms", r.ms))
                    }
                }
                "RUN_RECIPE" -> store.recipe(intent.getStringExtra("id") ?: "")?.let { r ->
                    val slots = Matcher.examples(r).toMutableMap()
                    intent.getStringExtra("slots")?.let { s -> JSONObject(s).let { j -> j.keys().forEach { k -> slots[k] = j.getString(k) } } }
                    executor.start(r, slots, "(dev) ${r.optString("name")}")
                }
                "STOP" -> executor.cancel()
                "EXPLAIN" -> store.recipe(intent.getStringExtra("id") ?: "")?.let { r ->
                    val st = r.getJSONArray("steps").getJSONObject((intent.getStringExtra("step") ?: "0").toInt())
                    val snap = snapshot()
                    val ranked = Resolver.rank(st, Matcher.examples(r), snap, r.getString("app"))
                    Dbg.log("EXPLAIN ${Generaliser.describe(st, Matcher.examples(r))} candidates=${ranked.size}")
                    ranked.take(6).forEach { m ->
                        Dbg.log("  %.2f %s [%s] '%s' %s :: %s".format(m.score, m.node.shortCls, m.node.shortId,
                            (m.node.label.ifEmpty { snap.labelsIn(m.node, 1).firstOrNull() ?: "" }).take(50), m.node.bounds.toShortString(), m.why))
                    }
                }
                "GUARD" -> {
                    val s = snapshot()
                    Dbg.log("GUARD app=${s.appPkg} screen=${com.prism.tva.core.Guard.screenBlock(s, s.appPkg) ?: "clear"}")
                }
                "DELETE" -> store.deleteRecipe(intent.getStringExtra("id") ?: "")
                "LIST" -> store.recipes().forEach { r ->
                    Dbg.log("RECIPE ${r.optString("id")} \"${r.optString("description")}\" \"${r.optString("template")}\" steps=${r.getJSONArray("steps").length()}")
                }
                "DUMP" -> {
                    val s = snapshot()
                    val f = File(store.root, "dumps").apply { mkdirs() }
                    File(f, (intent.getStringExtra("name") ?: "dump") + ".json").writeText(s.toJson().toString())
                    Dbg.log("DUMP nodes=${s.nodes.size} app=${s.appPkg} activity=${s.activity}")
                }
            }
        }
    }
}
