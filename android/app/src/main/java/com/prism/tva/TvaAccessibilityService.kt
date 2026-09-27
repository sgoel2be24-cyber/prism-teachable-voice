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
import com.prism.tva.recipe.Generaliser
import com.prism.tva.recipe.Matcher
import com.prism.tva.recipe.Store
import com.prism.tva.run.Executor
import com.prism.tva.teach.Recorder
import com.prism.tva.ui.Hud
import com.prism.tva.ui.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

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
    lateinit var recorder: Recorder
    lateinit var executor: Executor
    var homePkg: String? = null
        private set

    @Volatile var lastEventAt = 0L
        private set
    @Volatile var lastWindowClass: String? = null
        private set

    override fun onServiceConnected() {
        Dbg.init(this)
        store = Store(this)
        hud = Hud(this)
        speaker = Speaker(this)
        recorder = Recorder(this).also { r -> r.onFinished = { rec -> onTeachFinished(rec) } }
        executor = Executor(this)
        homePkg = Actions.homePackage(this)
        val filter = IntentFilter().apply {
            listOf("TEACH_START", "TEACH_STOP", "TEACH_CANCEL", "RUN", "RUN_RECIPE", "STOP", "LIST", "DUMP", "DELETE", "EXPLAIN")
                .forEach { addAction(DEV + it) }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(devCommands, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(devCommands, filter)
        instance = this
        Dbg.log("SERVICE_CONNECTED home=$homePkg recipes=${store.recipes().size}")
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

    // ------------------------------------------------------------------ teach & run entry points

    fun startTeaching(command: String) {
        if (executor.running) executor.cancel()
        recorder.start(command)
    }

    private fun onTeachFinished(rec: JSONObject?) {
        if (rec == null) return
        val recipe = Generaliser.build(rec, this, homePkg, packageName)
        if (recipe.optString("app").isEmpty() || recipe.getJSONArray("steps").length() < 2) {
            speaker.say("I didn't see any steps in an app. Let's try teaching that again.")
            Dbg.log("TEACH_EMPTY ${rec.optString("id")}")
            return
        }
        store.saveRecipe(recipe)
        val n = recipe.getJSONArray("steps").length()
        Dbg.log("LEARNED ${recipe.getString("id")} template=\"${recipe.optString("template")}\" steps=$n noise=${recipe.getJSONArray("noise").length()}\n${recipe.toString(1)}")
        speaker.say("Learned: ${recipe.optString("name")}. $n steps.")
        hud.show("Learned: ${recipe.optString("template")} · $n steps", listOf("OK" to { hud.hide() }), autoHideMs = 8000)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("recipe", recipe.getString("id")))
    }

    /** Handles a spoken or typed command: run a learned flow, or offer to learn it. */
    fun handleUtterance(utterance: String) {
        val m = Matcher.match(utterance, store.recipes())
        if (m == null) {
            Dbg.log("NO_MATCH \"$utterance\"")
            speaker.say("I haven't learned that yet. Do you want to teach me?")
            hud.show("Not learned: \"$utterance\"", listOf("Teach it" to { startTeaching(utterance) }, "No" to { hud.hide() }), autoHideMs = 15000)
            return
        }
        Dbg.log("MATCH \"$utterance\" -> ${m.recipe.optString("id")} (${m.how}) ${m.slots}")
        executor.start(m.recipe, m.slots, utterance)
    }

    // ------------------------------------------------------------------ development hooks (adb)

    private val devCommands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action?.removePrefix(DEV)) {
                "TEACH_START" -> startTeaching(intent.getStringExtra("command") ?: "untitled")
                "TEACH_STOP" -> recorder.finish()
                "TEACH_CANCEL" -> recorder.stop(save = false)
                "RUN" -> handleUtterance(intent.getStringExtra("utterance") ?: "")
                "RUN_RECIPE" -> store.recipe(intent.getStringExtra("id") ?: "")?.let { r ->
                    val slots = Matcher.examples(r).toMutableMap()
                    intent.getStringExtra("slots")?.let { s -> JSONObject(s).let { j -> j.keys().forEach { k -> slots[k] = j.getString(k) } } }
                    executor.start(r, slots, "(dev) ${r.optString("name")}")
                }
                "STOP" -> executor.cancel()
                "EXPLAIN" -> store.recipe(intent.getStringExtra("id") ?: "")?.let { r ->
                    val st = r.getJSONArray("steps").getJSONObject((intent.getStringExtra("step") ?: "0").toInt())
                    val snap = snapshot()
                    val ranked = com.prism.tva.run.Resolver.rank(st, Matcher.examples(r), snap, r.getString("app"))
                    Dbg.log("EXPLAIN ${Generaliser.describe(st, Matcher.examples(r))} candidates=${ranked.size}")
                    ranked.take(6).forEach { m ->
                        Dbg.log("  %.2f %s [%s] '%s' %s :: %s".format(m.score, m.node.shortCls, m.node.shortId,
                            (m.node.label.ifEmpty { snap.labelsIn(m.node, 1).firstOrNull() ?: "" }).take(50), m.node.bounds.toShortString(), m.why))
                    }
                }
                "DELETE" -> store.deleteRecipe(intent.getStringExtra("id") ?: "")
                "LIST" -> store.recipes().forEach { r ->
                    Dbg.log("RECIPE ${r.optString("id")} \"${r.optString("template")}\" steps=${r.getJSONArray("steps").length()}")
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
