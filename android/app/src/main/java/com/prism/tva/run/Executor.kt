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
        val c = Ctx(recipe, slots, app, fill(recipe.optString("description").ifEmpty { recipe.optString("name") }, slots)
            .ifEmpty { recipe.optString("name") } + if (appOverride != null) " (in ${Actions.appLabel(svc, app)})" else "")
        val goals = recipe.optJSONArray("goals") ?: JSONArray()
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
                val desc = stepText(st, c)
                svc.hud.update("${i + 1}/${steps.length()} · ${Generaliser.describe(st, slots)}")
                val s0 = System.currentTimeMillis()
                val r = runStep(st, c)
                record(i, desc, r, System.currentTimeMillis() - s0)
                if (!r.ok) { outcome = r.outcome; reason = r.note; stoppedAt = i; break }
                c.history.add(Generaliser.describe(st, slots))
            }
        } catch (e: CancellationException) {
            outcome = "stopped"; reason = "stopped by the user"
        } catch (e: Exception) {
            outcome = "failed"; reason = e.toString()
            Dbg.log("RUN_ERROR $e")
        }
        val rec = JSONObject()
            .put("id", runId).put("utterance", utterance)
            .put("recipe", recipe.optString("id")).put("recipeName", c.task)
            .put("app", app).put("slots", JSONObject(slots as Map<*, *>)).put("outcome", outcome).put("reason", reason)
            .put("stoppedAt", stoppedAt).put("stepCount", steps.length()).put("llmCalls", c.llmCalls)
            .put("startedAt", t0).put("ms", System.currentTimeMillis() - t0).put("steps", log)
        withContext(NonCancellable) {
            svc.store.appendRun(rec)
            val msg = when (outcome) {
                "success" -> "Done: ${c.task}. Please review it and complete the payment yourself."
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
        if (!ok) ok = Actions.tap(svc, n.bounds.exactCenterX(), n.bounds.exactCenterY(), long)
        return ok
    }

    private suspend fun tap(st: JSONObject, c: Ctx): StepResult {
        val start = System.currentTimeMillis()
        var scrolls = 0
        var llmActs = 0
        var method = "match"
        while (true) {
            svc.settle(400, 4000)
            val snap = svc.snapshot()
            guardScreen(snap, c.app)?.let { return it }
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
            if (elapsed < 2500) { delay(400); continue } // let a loading screen finish first
            // Fast path found nothing clear: let the LLM look at the screen. A stuck step is reported
            // within 30 s rather than guessing on (e.g. an app switched to another language).
            if (Fireworks.available && llmActs < 8 && elapsed < 26000) {
                llmActs++
                method = "llm"
                val r = llmAct(stepText(st, c) + demoHint(st), c, snap) ?: continue
                if (r.second) return r.first // step finished, handed over, or failed
                continue
            }
            if (elapsed > (if (llmActs > 0) 30000 else 15000) || scrolls >= 12) {
                return StepResult(false, "failed", "notfound", "I couldn't find ${Generaliser.describe(st, c.slots).removePrefix("Tap ")} on this screen")
            }
            if (scrollOnce(snap, c.app, forward = scrolls < 6)) scrolls++ else delay(400)
        }
    }

    /**
     * One LLM decision on the current screen. Returns (result, final): final=true means the step is
     * finished (done, handed over or failed); false means keep trying (e.g. a pop-up was dismissed).
     */
    private suspend fun llmAct(goal: String, c: Ctx, snap: Snapshot): Pair<StepResult, Boolean>? {
        c.llmCalls++
        val d = Brain.decide(c.task, goal, c.history, snap, c.app)
        if (d == null) {
            delay(300)
            return null
        }
        Dbg.log("LLM_DECIDE ${d.action} el=${d.node?.label?.take(40)} completes=${d.completesStep} :: ${d.reason}")
        when (d.action) {
            "tap" -> {
                val n = d.node ?: return null
                guardTap(snap, n)?.let { return it to true }
                val ok = press(n)
                svc.settle(400, 3000)
                c.history.add("tapped \"${n.label.ifEmpty { snap.labelsIn(n, 1).firstOrNull() ?: n.shortId }}\"")
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
                return StepResult(true, "success", "llm", d.reason) to false
            }
            "back" -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                svc.settle(400, 3000)
                return StepResult(true, "success", "llm", d.reason) to false
            }
            "done" -> return StepResult(true, "success", "llm-done", d.reason) to true
            "ask" -> {
                if (c.asks >= 2) return StepResult(false, "asked", "llm", d.question ?: d.reason) to true
                c.asks++
                val ans = svc.asker.ask(d.question ?: "I'm stuck here. What should I do?")
                    ?: return StepResult(false, "asked", "ask", d.question ?: d.reason) to true
                c.history.add("the user answered \"${d.question}\" with \"$ans\"")
                return StepResult(true, "success", "ask", ans) to false
            }
            else -> return StepResult(false, "failed", "llm", d.reason.ifEmpty { "the assistant couldn't decide" }) to true
        }
    }

    /** Makes a goal the demo never showed true (e.g. quantity 2, deliver to Work), LLM-driven. */
    private suspend fun runGoal(goal: String, c: Ctx): StepResult {
        if (!Fireworks.available) return StepResult(false, "failed", "goal", "I can't adjust that without the language model")
        repeat(7) {
            svc.settle(400, 4000)
            val snap = svc.snapshot()
            guardScreen(snap, c.app)?.let { return it }
            val r = llmAct("$goal. Reply done as soon as this is true on screen.", c, snap) ?: return@repeat
            if (r.second) return StepResult(r.first.ok, r.first.outcome, "goal", r.first.note)
        }
        return StepResult(false, "failed", "goal", "I couldn't complete: $goal")
    }

    /**
     * Scrolls the page the way a thumb would: a vertical swipe through the middle of the screen. (The
     * "largest scrollable element" is often a horizontal image carousel, which an accessibility
     * scroll would move sideways.) Falls back to an accessibility scroll if the swipe can't be sent.
     */
    private suspend fun scrollOnce(snap: Snapshot, app: String, forward: Boolean): Boolean {
        val x = snap.screenW * 0.5f
        val (y1, y2) = if (forward) snap.screenH * 0.68f to snap.screenH * 0.32f else snap.screenH * 0.32f to snap.screenH * 0.68f
        var ok = Actions.swipe(svc, x, y1, x, y2, 380)
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
