package com.prism.tva.run

import android.accessibilityservice.AccessibilityService
import com.prism.tva.TvaAccessibilityService
import com.prism.tva.core.Actions
import com.prism.tva.core.Dbg
import com.prism.tva.core.Guard
import com.prism.tva.core.Snapshot
import com.prism.tva.core.Text
import com.prism.tva.core.UiNode
import com.prism.tva.llm.Brain
import com.prism.tva.llm.Fireworks
import com.prism.tva.recipe.Generaliser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Replays a recipe with new values. Escalation per step:
 *   1. fast path: scored element match on the live screen (no network), scrolling if needed;
 *   2. LLM: shown the screen and the step's intent, it picks the action (dismiss a pop-up, find the
 *      item a different way, handle a screen the demo never showed);
 *   3. the user: one spoken question when the LLM can't tell what to do or a value is missing;
 *   4. hard stop at payment, login, OTP and password screens (Guard), handing control back.
 * Goals (quantity, address) that the demonstration didn't show are made true by the LLM at the
 * point the recipe marks. Every run is logged.
 */
class Executor(private val svc: TvaAccessibilityService) {

    class StepResult(val ok: Boolean, val outcome: String, val method: String, val note: String)

    private class Ctx(
        val recipe: JSONObject,
        val slots: MutableMap<String, String>,
        val app: String,
        val task: String,
        val history: ArrayList<String> = ArrayList(),
        var llmCalls: Int = 0,
        var asks: Int = 0,
        val cross: Boolean = false, // running a recipe in a different app than it was taught in
        val deadEnds: HashMap<Int, MutableSet<String>> = HashMap(), // screen signature -> actions that did nothing there
        val llmTapped: MutableList<String> = ArrayList(), // what the LLM tapped while working on the current step
    )

    @Volatile private var job: Job? = null
    val running get() = job?.isActive == true

    fun start(recipe: JSONObject, slots: Map<String, String>, utterance: String, appOverride: String? = null) {
        job?.cancel()
        job = svc.scope.launch { run(recipe, slots, utterance, appOverride) }
    }

    fun cancel() { job?.cancel() }

    private fun fill(template: String, slots: Map<String, String>): String {
        var s = template
        slots.forEach { (k, v) -> s = s.replace("{$k}", v) }
        return s.replace(Regex("\\{[a-z0-9_]+\\}"), "").replace(Regex("\\s+"), " ").trim()
    }

    private fun stepText(st: JSONObject, c: Ctx): String {
        val intent = st.optString("intent")
        val base = Generaliser.describe(st, c.slots)
        return if (intent.isNotBlank()) "${fill(intent, c.slots)} ($base)" else base
    }

    /** What the element looked like in the demonstration, to steer the LLM when the screen differs. */
    private fun demoHint(st: JSONObject): String {
        val t = st.optJSONObject("target") ?: return ""
        val label = t.optString("leafLabel").ifEmpty { t.optString("label") }.take(60)
        val card = t.optJSONArray("rowLabels")?.let { a -> (0 until minOf(3, a.length())).map { a.getString(it).take(40) } }.orEmpty()
        return buildString {
            append(" In the demonstration this was a ${if (t.optBoolean("editable")) "text box" else "button"}")
            if (label.isNotEmpty()) append(" labelled \"").append(label).append('"')
            if (t.optString("id").isNotEmpty()) append(" (id ").append(t.optString("id")).append(')')
            if (card.isNotEmpty()) append(", inside a card showing ").append(card.joinToString(" / ") { "\"$it\"" })
            append('.')
        }
    }

    private suspend fun run(recipe: JSONObject, slotsIn: Map<String, String>, utterance: String, appOverride: String?): JSONObject {
        val steps = recipe.getJSONArray("steps")
        val app = appOverride ?: recipe.getString("app")
        val slots = HashMap(slotsIn)
        val base = fill(recipe.optString("description").ifEmpty { recipe.optString("name") }, slots).ifEmpty { recipe.optString("name") }
        val c = Ctx(recipe, slots, app,
            if (appOverride == null) base
            else "$base — taught in ${recipe.optString("appLabel")}, now doing the same in ${Actions.appLabel(svc, app)} (its buttons and screens differ)",
            cross = appOverride != null)
        val goals = JSONArray((recipe.optJSONArray("goals") ?: JSONArray()).toString())
        // Values the user gave that no step or goal uses ("…and deliver to Work" when the demo never
        // touched the address): make them true at the end, before handing over for payment.
        run {
            val used = HashSet<String>()
            for (i in 0 until steps.length()) steps.getJSONObject(i).let { used += it.optString("textSlot"); used += it.optString("anchorSlot") }
            for (i in 0 until goals.length()) used += goals.getJSONObject(i).optString("slot")
            val taught = HashMap<String, String>()
            recipe.optJSONArray("slots")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { taught[it.getString("name")] = it.optString("example") } }
            val usedValues = slots.filterKeys { it in used }.values.map { Text.norm(it) }.toSet()
            slots.filter { (k, v) -> k !in used && v.isNotBlank() && Text.norm(v) != Text.norm(taught[k].orEmpty()) &&
                Text.norm(v) !in usedValues && k.matches(Regex("[a-z][a-z0-9_]{0,20}")) }
                .forEach { (k, _) ->
                    Dbg.log("EXTRA_GOAL $k=${slots[k]}")
                    val place = Regex("address|location|deliver").containsMatchIn(k)
                    goals.put(JSONObject().put("slot", k).put("default", "")
                        // Delivery apps pick the address on the home screen, before the restaurant
                        // list (which depends on it): do it right after opening the app.
                        .put("before", if (place && steps.length() > 1) 1 else -1)
                        .put("instruction", if (place)
                            "Set the delivery address to the saved address named \"{$k}\": tap the current delivery location (usually at the top of the home screen), then pick \"{$k}\" from the saved addresses. Don't edit or add an address."
                        else "Make sure the ${k.replace('_', ' ')} is {$k} (open the cart or checkout if that's where it is set; do not pay)"))
                }
        }
        val runId = "run_" + System.currentTimeMillis()
        val log = JSONArray()
        var outcome = "success"
        var reason = ""
        var stoppedAt = -1
        val t0 = System.currentTimeMillis()
        Dbg.log("RUN_START $runId recipe=${recipe.optString("id")} app=$app slots=$slots")
        svc.hud.show("Running: ${c.task}", listOf("Stop" to { cancel() }))
        suspend fun record(i: Int, desc: String, r: StepResult, ms: Long) {
            log.put(JSONObject().put("i", i).put("desc", desc).put("ok", r.ok).put("outcome", r.outcome)
                .put("method", r.method).put("ms", ms).put("note", r.note))
            Dbg.log("STEP $i ${if (r.ok) "ok" else r.outcome} [${r.method}] $desc :: ${r.note}")
        }
        try {
            loop@ for (i in 0..steps.length()) {
                // Goals the demo didn't show (quantity, address) are made true right before step i.
                for (g in 0 until goals.length()) {
                    val goal = goals.getJSONObject(g)
                    val before = goal.optInt("before", -1).let { if (it < 0 || it > steps.length()) steps.length() else it }
                    if (before != i) continue
                    val value = slots[goal.optString("slot")].orEmpty()
                    if (value.isBlank() || Text.norm(value) == Text.norm(goal.optString("default"))) continue
                    val desc = fill(goal.optString("instruction").ifEmpty { "Make sure ${goal.optString("slot")} is {${goal.optString("slot")}}" }, slots)
                    svc.hud.update("Adjusting · $desc")
                    val s0 = System.currentTimeMillis()
                    val r = runGoal(desc, c)
                    record(i, desc, r, System.currentTimeMillis() - s0)
                    if (!r.ok) { outcome = r.outcome; reason = r.note; stoppedAt = i; break@loop }
                    c.history.add(desc)
                }
                if (i == steps.length()) break
                val st = steps.getJSONObject(i)
                if (st.optBoolean("noise")) { Dbg.log("STEP $i skipped (marked as noise)"); continue }
                // A value this step needs but the user didn't give: ask now, mid-flow.
                for (key in listOf("textSlot", "anchorSlot")) {
                    val name = st.optString(key)
                    if (name.isNotEmpty() && slots[name].isNullOrBlank()) {
                        val ans = askFor(name, c)
                        if (ans == null) {
                            outcome = "asked"; reason = "I needed the ${meaning(name, c)} and didn't get an answer"; stoppedAt = i
                            break@loop
                        }
                        slots[name] = ans
                    }
                }
                val isLaunch = st.optString("kind") == "launch"
                val short = if (isLaunch) "Open ${Actions.appLabel(svc, c.app)}" else Generaliser.describe(st, slots)
                val desc = if (isLaunch) short else stepText(st, c)
                svc.hud.update("${i + 1}/${steps.length()} · $short")
                val s0 = System.currentTimeMillis()
                val r = runStep(st, c)
                record(i, desc, r, System.currentTimeMillis() - s0)
                if (!r.ok) { outcome = r.outcome; reason = r.note; stoppedAt = i; break }
                c.history.add(short)
            }
        } catch (e: CancellationException) {
            outcome = "stopped"; reason = "stopped by the user"
        } catch (e: Exception) {
            outcome = "failed"; reason = e.toString()
            Dbg.log("RUN_ERROR $e")
        }
        // Values asked for mid-run are known now: name the task with them ("Add margherita pizza…").
        val done = fill(recipe.optString("description").ifEmpty { recipe.optString("name") }, slots).ifEmpty { c.task }
        val rec = JSONObject()
            .put("id", runId).put("utterance", utterance)
            .put("recipe", recipe.optString("id")).put("recipeName", done)
            .put("app", app).put("slots", JSONObject(slots as Map<*, *>)).put("outcome", outcome).put("reason", reason)
            .put("stoppedAt", stoppedAt).put("stepCount", steps.length()).put("llmCalls", c.llmCalls)
            .put("startedAt", t0).put("ms", System.currentTimeMillis() - t0).put("steps", log)
        withContext(NonCancellable) {
            svc.store.appendRun(rec)
            val msg = when (outcome) {
                "success" -> "Done: $done. Please review it and complete the payment yourself."
                "handover" -> "I've stopped at $reason. Your turn."
                "stopped" -> "Stopped."
                "asked" -> "I've paused: $reason."
                else -> "I couldn't finish: $reason."
            }
            svc.speaker.say(msg)
            svc.hud.show(msg, listOf("OK" to { svc.hud.hide() }), autoHideMs = 9000)
            Dbg.log("RUN_END $runId $outcome ${System.currentTimeMillis() - t0}ms llm=${c.llmCalls} $reason")
        }
        return rec
    }

    private fun meaning(slot: String, c: Ctx): String {
        val arr = c.recipe.optJSONArray("slots") ?: return slot
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            if (s.optString("name") == slot) return s.optString("meaning").ifEmpty { slot }
        }
        return slot
    }

    private suspend fun askFor(slot: String, c: Ctx): String? {
        c.asks++
        val arr = c.recipe.optJSONArray("slots")
        val q = arr?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name") == slot }?.optString("question") }
        return svc.asker.ask(q?.takeIf { it.isNotBlank() } ?: "Which $slot should I use?")
    }

    private suspend fun runStep(st: JSONObject, c: Ctx): StepResult =
        when (st.optString("kind")) {
            "launch" -> launch(c.app)
            "back" -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                svc.settle(400, 3000)
                StepResult(true, "success", "global", "")
            }
            "tap", "longpress" -> tap(st, c)
            "type" -> type(st, c)
            else -> StepResult(true, "success", "skip", "unknown kind ${st.optString("kind")}")
        }

    private suspend fun launch(pkg: String): StepResult {
        if (!Actions.launch(svc, pkg)) return StepResult(false, "failed", "launch", "the app isn't installed")
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            if (svc.snapshot().appPkg == pkg) {
                svc.settle(700, 6000)
                return StepResult(true, "success", "launch", "")
            }
        }
        return StepResult(false, "failed", "launch", "the app did not open")
    }

    private fun guardScreen(snap: Snapshot, app: String): StepResult? =
        Guard.screenBlock(snap, app)?.let { StepResult(false, "handover", "guard", Guard.spoken(it)) }

    private fun guardTap(snap: Snapshot, n: UiNode): StepResult? {
        val labels = listOf(n.label) + snap.labelsIn(n, 6)
        return Guard.actionBlock(labels)?.let {
            StepResult(false, "handover", "guard", "${Guard.spoken(it)} (\"${labels.firstOrNull { l -> l.isNotEmpty() } ?: ""}\")")
        }
    }

    private suspend fun press(n: UiNode, long: Boolean = false): Boolean {
        var ok = if (long) Actions.longClick(n) else (n.clickable && Actions.click(n))
        if (!ok) ok = throughHud { Actions.tap(svc, n.bounds.exactCenterX(), n.bounds.exactCenterY(), long) }
        return ok
    }

    /** Runs a real gesture with the status pill made touch-transparent, so the gesture can't hit it. */
    private suspend fun <T> throughHud(block: suspend () -> T): T {
        svc.hud.setTouchable(false)
        delay(80)
        try { return block() } finally { svc.hud.setTouchable(true) }
    }

    private suspend fun tap(st: JSONObject, c: Ctx): StepResult {
        val prevTapped = c.llmTapped.toSet()
        c.llmTapped.clear()
        var start = System.currentTimeMillis()
        var scrolls = 0
        var preScrolls = 0
        var pageSearched = false
        var llmActs = 0
        var method = "match"
        var checks = 0 // times we re-checked an LLM "this finishes the step" claim on the resulting screen
        var verifying = false
        while (true) {
            svc.settle(400, 2500) // shopping pages never go fully quiet (autoplaying carousels)
            val snap = svc.snapshot()
            guardScreen(snap, c.app)?.let { return it }
            if (verifying) {
                // The LLM said its last tap finished the step. Check the resulting screen instead of
                // trusting it (e.g. "Add to bag" does nothing until a size is chosen).
                if (llmActs >= (if (c.cross) 20 else 12) || System.currentTimeMillis() - start > (if (c.cross) 80000 else 60000)) {
                    return StepResult(false, "failed", "llm", "I couldn't confirm: ${Generaliser.describe(st, c.slots)}")
                }
                llmActs++
                val t = System.currentTimeMillis()
                val r = llmAct("Check whether this is now done: ${stepText(st, c)}. If it is, reply done. " +
                    "If something is still needed first (a required size or option, a confirmation sheet), do that next.", c, snap, strict = true)
                    ?: continue
                if (r.first.method == "ask") start += System.currentTimeMillis() - t
                if (!r.second) { continue }
                // Another "this tap finishes it" claim: look again. Only a done with proof on screen
                // (or a hand-over / question / failure) ends the step; the caps above bound the loop.
                if (r.first.method == "llm" && r.first.ok) { checks++; continue }
                return r.first
            }
            val m = Resolver.resolve(st, c.slots, snap, c.app)
            if (m != null) {
                guardTap(snap, m.node)?.let { return it }
                val ok = press(m.node, st.optString("kind") == "longpress")
                svc.settle(350, 3000)
                if (scrolls > 0 && method == "match") method = "scroll+match"
                return StepResult(ok, if (ok) "success" else "failed", method,
                    "score=%.1f next=%.1f %s".format(m.score, m.runnerUp, m.why))
            }
            val elapsed = System.currentTimeMillis() - start
            // The LLM already pressed this very button while finishing the previous step (e.g. it
            // tapped "Add item" itself): don't look for it again.
            val want = Text.stable(st.optJSONObject("target")?.let { it.optString("leafLabel").ifEmpty { it.optString("label") } }.orEmpty())
            if (llmActs == 0 && want.length >= 3 && want in prevTapped) {
                return StepResult(true, "success", "skip", "already done while finishing the previous step")
            }
            if (elapsed < 2500) { delay(400); continue } // let a loading screen finish first
            // Web-based result pages can sit blank (only the top bar and tab bar) for several seconds
            // on a slow connection; scrolling or asking the LLM about an empty page goes nowhere.
            if (elapsed < 15000 && blank(snap, c.app)) { delay(500); continue }
            // Cheap before clever: the element is often just below the fold (a sponsored banner
            // pushed the first result down). Up to three thumb scrolls with the fast matcher, then the LLM.
            if (preScrolls < (if (st.optInt("scrollsBefore") >= 8) 1 else 3) && !c.cross) {
                val s0 = sig(snap, c.app)
                scrollOnce(snap, c.app, forward = true)
                preScrolls++
                if (sig(svc.snapshot(), c.app) == s0) preScrolls = 3 else scrolls++ // screen doesn't scroll
                continue
            }
            // An item far down a long menu: use the page's own "Search in …" box once, then match again.
            val anchorValue = c.slots[st.optString("anchorSlot")].orEmpty()
            if (st.optInt("scrollsBefore") >= 8 && !pageSearched && anchorValue.isNotEmpty() && !c.cross) {
                pageSearched = true
                if (searchInPage(snap, anchorValue, c)) continue
            }
            // Fast path found nothing clear: let the LLM look at the screen. A stuck step is reported
            // within 30 s rather than guessing on (e.g. an app switched to another language).
            // In another app every screen is new, and an item far down a long list (the demo scrolled a
            // lot) may need the page's own search: both get a bigger budget.
            val far = st.optInt("scrollsBefore") >= 8
            val big = c.cross || far
            if (Fireworks.available && llmActs < (if (big) 12 else 8) && elapsed < (if (big) 45000 else 26000)) {
                llmActs++
                method = "llm"
                val t = System.currentTimeMillis()
                val hint = if (far) " In the demonstration the user scrolled a long way down to find it; if this screen has its own search box (e.g. \"Search in …\"), search for it there instead of scrolling." else ""
                val r = llmAct(stepText(st, c) + demoHint(st) + hint, c, snap) ?: continue
                if (r.first.method == "ask") start += System.currentTimeMillis() - t // waiting for the user doesn't count
                if (r.second && r.first.method == "llm" && r.first.ok) { verifying = true; checks++; continue }
                if (r.second) return r.first // done, handed over, asked, or failed
                continue
            }
            if (elapsed > (if (llmActs > 0) (if (big) 50000 else 30000) else 15000) || scrolls >= 12) {
                return StepResult(false, "failed", "notfound", "I couldn't find ${Generaliser.describe(st, c.slots).removePrefix("Tap ")} on this screen")
            }
            if (scrollOnce(snap, c.app, forward = scrolls < 6)) scrolls++ else delay(400)
        }
    }

    /**
     * One LLM decision on the current screen. Returns (result, final): final=true means the step is
     * finished (done, handed over or failed); false means keep trying (e.g. a pop-up was dismissed).
     */
    private suspend fun llmAct(goal: String, c: Ctx, snap: Snapshot, strict: Boolean = false): Pair<StepResult, Boolean>? {
        c.llmCalls++
        val before = sig(snap, c.app)
        val dead = c.deadEnds.getOrPut(before) { HashSet() }
        val d = Brain.decide(c.task, goal, c.history, snap, c.app, c.slots.filterValues { it.isNotBlank() }, dead.toList())
        if (d == null) {
            delay(300)
            return null
        }
        val key = d.action + (d.node?.let { n -> " \"" + Brain.display(snap, n, 40).ifEmpty { n.shortId.ifEmpty { "element at y=${n.bounds.centerY() * 100 / snap.screenH}%" } } + "\"" } ?: "")
        Dbg.log("LLM_DECIDE $key completes=${d.completesStep} :: ${d.reason}")
        if (key in dead && d.action != "done" && d.action != "ask") {
            // The model keeps picking something that already did nothing here; don't let it spin.
            c.history.add("refused to repeat $key (it did nothing on this screen)")
            return StepResult(true, "success", "llm", "repeat refused") to false
        }
        // Tells the LLM what its action actually did, so it doesn't repeat a dead end, plus the
        // headline of a newly opened screen (e.g. the product it opened).
        fun effect(): String {
            val after = svc.snapshot()
            if (same(snap, after, c.app)) { dead.add(key); return "nothing changed" }
            val head = after.visibleLabels(c.app, 40).filter { !Brain.idLike(it) && it.trim().split(' ').size >= 3 }.take(2)
            // Buttons that stayed put but changed wording are the clearest sign an action took
            // effect ("Add to Bag" -> "Go to Bag").
            val prev = snap.appNodes(c.app).filter { it.clickable }.associateBy { it.bounds.flattenToString() }
            val flips = after.appNodes(c.app).filter { it.clickable }.mapNotNull { n ->
                val o = prev[n.bounds.flattenToString()] ?: return@mapNotNull null
                val was = Brain.display(snap, o, 40)
                val now = Brain.display(after, n, 40)
                if (was.isNotEmpty() && now.isNotEmpty() && was != now) "\"$was\" now reads \"$now\"" else null
            }.take(2)
            val what = (if (head.isEmpty()) "" else " showing " + head.joinToString(", ") { "\"${it.take(60)}\"" }) +
                (if (flips.isEmpty()) "" else "; " + flips.joinToString("; "))
            return if (after.activity != snap.activity) "a new screen opened (${after.activity?.substringAfterLast('.') ?: "?"})$what"
            else "the screen changed$what"
        }
        when (d.action) {
            "tap" -> {
                val n = d.node ?: return null
                guardTap(snap, n)?.let { return it to true }
                // Tap like a finger where the model pointed: some apps accept an accessibility click
                // and ignore it (Zomato's location bar). Off-screen or under the keyboard: click.
                val cx = n.bounds.exactCenterX()
                val cy = n.bounds.exactCenterY()
                val reachable = n.bounds.width() > 0 && cx in 0f..snap.screenW.toFloat() && cy in 0f..snap.screenH.toFloat() &&
                    (snap.imeTop() < 0 || cy < snap.imeTop())
                var ok = reachable && throughHud { Actions.tap(svc, cx, cy, false) }
                if (!ok) ok = press(n)
                svc.settle(400, 3000)
                val what = Brain.display(snap, n, 50)
                if (ok) c.llmTapped.add(Text.stable(what))
                c.history.add("tapped ${if (what.isNotEmpty()) "\"$what\"" else "an unlabelled element"} to ${d.reason.take(60)} → ${effect()}")
                return if (d.completesStep) StepResult(ok, if (ok) "success" else "failed", "llm", d.reason) to true
                else StepResult(true, "success", "llm", d.reason) to false
            }
            "type" -> {
                val n = d.node ?: return null
                val ok = Actions.setText(n, d.text.orEmpty())
                svc.settle(400, 3000)
                c.history.add("typed \"${d.text}\"")
                return StepResult(ok, if (ok) "success" else "failed", "llm", d.reason) to d.completesStep
            }
            "scroll_down", "scroll_up" -> {
                scrollOnce(snap, c.app, forward = d.action == "scroll_down")
                val e = effect()
                c.history.add("${d.action.replace('_', ' ')} → ${if (e == "nothing changed") "nothing moved (this screen does not scroll)" else e}")
                return StepResult(true, "success", "llm", d.reason) to false
            }
            "scroll_right", "scroll_left" -> {
                // Sideways lists (sizes, colours, carousels): swipe inside the list's own row.
                val list = d.node?.let { n -> generateSequence(n) { snap.parentOf(it) }.firstOrNull { it.scrollable } ?: n }
                    ?: snap.appNodes(c.app).filter { it.scrollable && it.bounds.width() > it.bounds.height() * 3 }
                        .maxByOrNull { it.bounds.width() }
                val fwd = d.action == "scroll_right"
                if (list != null) {
                    val b = list.bounds
                    val y = b.exactCenterY()
                    val (x1, x2) = if (fwd) b.left + b.width() * 0.8f to b.left + b.width() * 0.2f else b.left + b.width() * 0.2f to b.left + b.width() * 0.8f
                    if (!throughHud { Actions.swipe(svc, x1, y, x2, y, 350) }) Actions.scroll(list, fwd)
                    svc.settle(350, 2000)
                }
                val e = if (list == null) "no sideways list found" else effect()
                c.history.add("${d.action.replace('_', ' ')} → $e")
                return StepResult(true, "success", "llm", d.reason) to false
            }
            "back" -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                svc.settle(400, 3000)
                c.history.add("went back → ${effect()}")
                return StepResult(true, "success", "llm", d.reason) to false
            }
            "done" -> {
                // Text in a search box is what we typed, not evidence that anything happened.
                val proof = d.node?.takeIf { !it.editable }?.let { Brain.display(snap, it, 60) }
                Dbg.log("LLM done proof=${proof ?: "none"}")
                if (strict && proof.isNullOrEmpty()) {
                    // A "done" we are double-checking needs something on screen that shows it.
                    c.history.add("said done but pointed at nothing on screen that proves it")
                    return StepResult(true, "success", "llm", "unproven done") to false
                }
                return StepResult(true, "success", "llm-done", d.reason + (proof?.let { " (\"$it\")" } ?: "")) to true
            }
            "ask" -> {
                // Questions about finding things on screen are the assistant's job, not the user's.
                val q = (d.question ?: "") + " " + d.reason
                if (Regex("scroll|do you see|don.?t see|can.?t see|cannot see|not visible|should i (tap|open|go|scroll|look)", RegexOption.IGNORE_CASE).containsMatchIn(q)) {
                    Dbg.log("LLM ask treated as scroll: ${d.question}")
                    scrollOnce(snap, c.app, forward = true)
                    val e = effect()
                    c.history.add("scroll down → ${if (e == "nothing changed") "nothing moved (this screen does not scroll)" else e}")
                    return StepResult(true, "success", "llm", "scrolled instead of asking") to false
                }
                if (c.asks >= 2) return StepResult(false, "asked", "llm", d.question ?: d.reason) to true
                c.asks++
                val ans = svc.asker.ask(d.question ?: "I'm stuck here. What should I do?")
                    ?: return StepResult(false, "asked", "ask", d.question ?: d.reason) to true
                c.history.add("the user answered \"${d.question}\" with \"$ans\"")
                val q2 = Text.norm(d.question ?: "")
                c.slots[listOf("size", "colour", "color", "variant", "flavour", "address", "time").firstOrNull { q2.contains(it) } ?: "answer"] = ans
                revealOption(ans, c)
                return StepResult(true, "success", "ask", ans) to false
            }
            else -> return StepResult(false, "failed", "llm", d.reason.ifEmpty { "the assistant couldn't decide" }) to true
        }
    }

    /** Makes a goal the demo never showed true (e.g. quantity 2, deliver to Work), LLM-driven. */
    private suspend fun runGoal(goal: String, c: Ctx): StepResult {
        if (!Fireworks.available) return StepResult(false, "failed", "goal", "I can't adjust that without the language model")
        repeat(10) {
            svc.settle(400, 3000)
            val snap = svc.snapshot()
            guardScreen(snap, c.app)?.let { return it }
            val r = llmAct("$goal. Reply done, pointing at the element that shows it, only once this is true on screen.",
                c, snap, strict = true) ?: return@repeat
            // A tap the model thinks finishes the goal (e.g. opening the address list) is checked on
            // the next screen rather than trusted.
            if (r.second && r.first.method == "llm" && r.first.ok) return@repeat
            if (r.second) return StepResult(r.first.ok, r.first.outcome, "goal", r.first.note)
        }
        return StepResult(false, "failed", "goal", "I couldn't complete: $goal")
    }

    /**
     * After the user names an option ("9"), make sure it is visible: sideways option rows (sizes,
     * colours) often continue off screen, and the model tends to give up on what it can't see.
     */
    private suspend fun revealOption(ans: String, c: Ctx) {
        val a = Text.norm(ans)
        if (a.isEmpty()) return
        fun shown(s: Snapshot) = s.appNodes(c.app).any {
            it.visible && it.label.isNotEmpty() && Text.norm(it.label).let { l -> l == a || (l.any(Char::isDigit) && l in a.split(' ')) }
        }
        repeat(4) {
            val s = svc.snapshot()
            if (shown(s)) return
            val list = s.appNodes(c.app).filter { it.visible && it.scrollable && it.bounds.width() > it.bounds.height() * 3 }
                .maxByOrNull { it.bounds.bottom } ?: return
            val b = list.bounds
            val y = b.exactCenterY()
            throughHud { Actions.swipe(svc, b.left + b.width() * 0.8f, y, b.left + b.width() * 0.2f, y, 350) }
            svc.settle(350, 2000)
            if (sig(svc.snapshot(), c.app) == sig(s, c.app)) return
            Dbg.log("revealOption: scrolled a sideways list looking for \"$ans\"")
        }
    }

    /**
     * Types [value] into the page's search box. On a restaurant page that is the menu search
     * ("Search in Domino's Pizza"), reached from the search bar at the top. Returns false when the
     * page has no search box.
     */
    private suspend fun searchInPage(snap: Snapshot, value: String, c: Ctx): Boolean {
        val inPage = Regex("^search (in|within) ", RegexOption.IGNORE_CASE)
        val notText = Regex("voice|scan|camera|mic|filter", RegexOption.IGNORE_CASE)
        val boxes = snap.appNodes(c.app).filter { n ->
            val words = Brain.display(snap, n, 80) + " " + Text.clean(n.hint) + " " + n.shortId
            (n.clickable || n.editable) && words.contains("search", ignoreCase = true) && !notText.containsMatchIn(words)
        }
        val box = boxes.firstOrNull { inPage.containsMatchIn(Brain.display(snap, it, 80).ifEmpty { Text.clean(it.hint) }) }
            ?: boxes.minByOrNull { it.bounds.top } ?: return false
        Dbg.log("PAGE_SEARCH \"$value\" via \"${Brain.display(snap, box, 40)}\"")
        if (!box.editable) { press(box); svc.settle(400, 3000) }
        val s2 = svc.snapshot()
        val field = s2.appNodes(c.app).firstOrNull { it.editable && it.focused }
            ?: s2.appNodes(c.app).firstOrNull { it.editable } ?: return false
        if (!Actions.setText(field, value)) return false
        svc.settle(700, 3000)
        c.history.add("searched this page for \"$value\"")
        return true
    }

    /** Nothing in the middle of the screen yet: the page is still loading. */
    private fun blank(s: Snapshot, app: String): Boolean {
        val top = (s.screenH * 0.2).toInt()
        val bottom = (s.screenH * 0.85).toInt()
        return s.appNodes(app).count {
            it.bounds.centerY() in top..bottom && it.bounds.height() < s.screenH / 2 && (it.label.isNotEmpty() || it.clickable)
        } < 3
    }

    /**
     * Whether an action left the screen as it was. Rotating hints and ticking timers change a label
     * or two on their own, so "same" means same screen, same windows and almost the same labels.
     */
    private fun same(a: Snapshot, b: Snapshot, app: String): Boolean {
        if (a.activity != b.activity) return false
        if (a.windows.count { it.pkg == app } != b.windows.count { it.pkg == app }) return false
        fun labels(s: Snapshot) = s.appNodes(app).asSequence().filter { it.label.isNotEmpty() }
            .map { it.normLabel + "@" + it.bounds.top / 16 }.take(80).toSet()
        val la = labels(a); val lb = labels(b)
        if (la.isEmpty() && lb.isEmpty()) return true
        val jaccard = la.intersect(lb).size.toDouble() / la.union(lb).size
        if (jaccard < 0.85) return false
        // A button that changed its words in place ("Add to Bag" -> "Go to Bag") is a real change,
        // unlike a search bar cycling through example queries.
        val rotating = Regex("^search\\b", RegexOption.IGNORE_CASE)
        val prev = a.appNodes(app).filter { it.clickable }.associateBy { it.bounds.flattenToString() }
        return b.appNodes(app).filter { it.clickable }.none { n ->
            val o = prev[n.bounds.flattenToString()] ?: return@none false
            val was = Brain.display(a, o, 40)
            val now = Brain.display(b, n, 40)
            was != now && !rotating.containsMatchIn(was) && !rotating.containsMatchIn(now)
        }
    }

    /** Cheap fingerprint of what's on screen (screen name + visible labels and their positions). */
    private fun sig(s: Snapshot, app: String): Int =
        (s.activity + "|" + s.nodes.asSequence()
            .filter { it.visible && it.label.isNotEmpty() && s.windows[it.window].pkg == app }
            .take(40).joinToString("|") { "${it.normLabel}@${it.bounds.top / 8}" }).hashCode()

    /**
     * Scrolls the page the way a thumb would: a vertical swipe through the middle of the screen. (The
     * "largest scrollable element" is often a horizontal image carousel, which an accessibility
     * scroll would move sideways.) Falls back to an accessibility scroll if the swipe can't be sent.
     */
    private suspend fun scrollOnce(snap: Snapshot, app: String, forward: Boolean): Boolean {
        val x = snap.screenW * 0.5f
        val (y1, y2) = if (forward) snap.screenH * 0.74f to snap.screenH * 0.26f else snap.screenH * 0.26f to snap.screenH * 0.74f
        var ok = throughHud { Actions.swipe(svc, x, y1, x, y2, 380) }
        if (!ok) ok = Resolver.mainScrollable(snap, app)?.let { Actions.scroll(it, forward) } ?: false
        svc.settle(350, 2000)
        return ok
    }

    private suspend fun type(st: JSONObject, c: Ctx): StepResult {
        if (st.optBoolean("secret")) return StepResult(false, "handover", "guard", "a password field")
        val textSlot = st.optString("textSlot")
        val value = if (textSlot.isNotEmpty()) c.slots[textSlot] ?: st.optString("text") else st.optString("text")
        val start = System.currentTimeMillis()
        var llmActs = 0
        while (System.currentTimeMillis() - start < 15000) {
            svc.settle(300, 3000)
            val snap = svc.snapshot()
            guardScreen(snap, c.app)?.let { return it }
            val field = snap.appNodes(c.app).firstOrNull { it.editable && it.focused }
                ?: Resolver.resolve(st, c.slots, snap, c.app)?.node
            if (field != null) {
                if (!Actions.setText(field, value)) return StepResult(false, "failed", "type", "couldn't type into the field")
                if (st.optBoolean("submit")) {
                    delay(500)
                    if (!Actions.imeEnter(field)) return StepResult(false, "failed", "type", "couldn't press search")
                    // The search key is sometimes swallowed while suggestions are still loading: if the
                    // box is still focused with our text and the keyboard is up, press it again.
                    for (attempt in 1..2) {
                        svc.settle(500, 2500)
                        val s = svc.snapshot()
                        val still = s.appNodes(c.app).firstOrNull { it.editable && it.focused }
                        if (still == null || s.imeTop() < 0 || Text.norm(still.label) != Text.norm(value)) break
                        Dbg.log("TYPE search key didn't take; pressing it again")
                        Actions.imeEnter(still)
                    }
                }
                svc.settle(500, 4000)
                return StepResult(true, "success", "type", "\"$value\"")
            }
            if (System.currentTimeMillis() - start > 2500 && Fireworks.available && llmActs < 3) {
                llmActs++
                val r = llmAct("Get to the text box for: ${stepText(st, c)} (don't type yet unless the box is already there)", c, snap)
                if (r != null && r.second && !r.first.ok) return r.first
                continue
            }
            delay(400)
        }
        return StepResult(false, "failed", "type", "no text field to type \"$value\" into")
    }
}
