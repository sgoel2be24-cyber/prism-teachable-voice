package com.prism.tva.run

import android.accessibilityservice.AccessibilityService
import com.prism.tva.TvaAccessibilityService
import com.prism.tva.core.Actions
import com.prism.tva.core.Dbg
import com.prism.tva.core.Guard
import com.prism.tva.core.Snapshot
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
 * Replays a recipe with new slot values. Per step: wait for the screen to settle, check the guard,
 * find the element (fast path, scrolling if needed), act, and log. Stops and hands over at payment,
 * login, OTP and password screens.
 */
class Executor(private val svc: TvaAccessibilityService) {

    class StepResult(val ok: Boolean, val outcome: String, val method: String, val note: String)

    @Volatile private var job: Job? = null
    val running get() = job?.isActive == true

    fun start(recipe: JSONObject, slots: Map<String, String>, utterance: String) {
        job?.cancel()
        job = svc.scope.launch { run(recipe, slots, utterance) }
    }

    fun cancel() { job?.cancel() }

    private suspend fun run(recipe: JSONObject, slots: Map<String, String>, utterance: String): JSONObject {
        val steps = recipe.getJSONArray("steps")
        val app = recipe.getString("app")
        val runId = "run_" + System.currentTimeMillis()
        val log = JSONArray()
        var outcome = "success"
        var reason = ""
        var stoppedAt = -1
        val t0 = System.currentTimeMillis()
        Dbg.log("RUN_START $runId recipe=${recipe.optString("id")} slots=$slots")
        svc.hud.show("Running: ${recipe.optString("name")}", listOf("Stop" to { cancel() }))
        try {
            for (i in 0 until steps.length()) {
                val st = steps.getJSONObject(i)
                val desc = Generaliser.describe(st, slots)
                svc.hud.update("${i + 1}/${steps.length()} · $desc")
                val s0 = System.currentTimeMillis()
                val r = runStep(st, slots, app)
                log.put(JSONObject().put("i", i).put("desc", desc).put("ok", r.ok).put("outcome", r.outcome)
                    .put("method", r.method).put("ms", System.currentTimeMillis() - s0).put("note", r.note))
                Dbg.log("STEP $i ${if (r.ok) "ok" else r.outcome} [${r.method}] $desc :: ${r.note}")
                if (!r.ok) {
                    outcome = r.outcome; reason = r.note; stoppedAt = i
                    break
                }
            }
        } catch (e: CancellationException) {
            outcome = "stopped"; reason = "Stopped by the user"
        } catch (e: Exception) {
            outcome = "failed"; reason = e.toString()
            Dbg.log("RUN_ERROR $e")
        }
        val rec = JSONObject()
            .put("id", runId).put("utterance", utterance)
            .put("recipe", recipe.optString("id")).put("recipeName", recipe.optString("name"))
            .put("slots", JSONObject(slots)).put("outcome", outcome).put("reason", reason)
            .put("stoppedAt", stoppedAt).put("stepCount", steps.length())
            .put("startedAt", t0).put("ms", System.currentTimeMillis() - t0).put("steps", log)
        withContext(NonCancellable) {
            svc.store.appendRun(rec)
            val msg = when (outcome) {
                "success" -> "Done. ${recipe.optString("name")} is ready. Please review it and complete payment yourself."
                "handover" -> "I've stopped at ${reason}. Your turn."
                "stopped" -> "Stopped."
                else -> "I couldn't finish: $reason"
            }
            svc.speaker.say(msg)
            svc.hud.show(msg, listOf("OK" to { svc.hud.hide() }), autoHideMs = 8000)
            Dbg.log("RUN_END $runId $outcome ${System.currentTimeMillis() - t0}ms $reason")
        }
        return rec
    }

    private suspend fun runStep(st: JSONObject, slots: Map<String, String>, app: String): StepResult =
        when (st.optString("kind")) {
            "launch" -> launch(st.optString("pkg", app))
            "back" -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                svc.settle(400, 3000)
                StepResult(true, "success", "global", "")
            }
            "tap", "longpress" -> tap(st, slots, app)
            "type" -> type(st, slots, app)
            else -> StepResult(true, "success", "skip", "unknown kind ${st.optString("kind")}")
        }

    private suspend fun launch(pkg: String): StepResult {
        if (!Actions.launch(svc, pkg)) return StepResult(false, "failed", "launch", "$pkg is not installed")
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

    private suspend fun tap(st: JSONObject, slots: Map<String, String>, app: String): StepResult {
        val start = System.currentTimeMillis()
        var scrolls = 0
        while (true) {
            svc.settle(400, 4000)
            val snap = svc.snapshot()
            guardScreen(snap, app)?.let { return it }
            val m = Resolver.resolve(st, slots, snap, app)
            if (m != null) {
                val labels = listOf(m.node.label) + snap.labelsIn(m.node, 6)
                Guard.actionBlock(labels)?.let {
                    return StepResult(false, "handover", "guard", "${Guard.spoken(it)} (\"${labels.firstOrNull { l -> l.isNotEmpty() } ?: ""}\")")
                }
                val long = st.optString("kind") == "longpress"
                var ok = if (long) Actions.longClick(m.node) else (m.node.clickable && Actions.click(m.node))
                if (!ok) ok = Actions.tap(svc, m.node.bounds.exactCenterX(), m.node.bounds.exactCenterY(), long)
                svc.settle(350, 3000)
                val how = (if (scrolls > 0) "scroll+" else "") + "match"
                return StepResult(ok, if (ok) "success" else "failed", how,
                    "score=%.1f next=%.1f %s".format(m.score, m.runnerUp, m.why))
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 15000 || scrolls >= 12) {
                return StepResult(false, "failed", "notfound", "I couldn't find ${Generaliser.describe(st, slots).removePrefix("Tap ")} on this screen")
            }
            // Give a loading screen a moment, then search by scrolling down, then back up.
            if (elapsed > 2500) {
                val sc = Resolver.mainScrollable(snap, app)
                if (sc != null) {
                    val forward = scrolls < 6
                    if (!Actions.scroll(sc, forward)) {
                        val b = sc.bounds
                        val (y1, y2) = if (forward) b.top + b.height() * 0.75f to b.top + b.height() * 0.3f
                        else b.top + b.height() * 0.3f to b.top + b.height() * 0.75f
                        Actions.swipe(svc, b.exactCenterX(), y1, b.exactCenterX(), y2)
                    }
                    scrolls++
                    continue
                }
            }
            delay(400)
        }
    }

    private suspend fun type(st: JSONObject, slots: Map<String, String>, app: String): StepResult {
        if (st.optBoolean("secret")) return StepResult(false, "handover", "guard", "a password field")
        val textSlot = st.optString("textSlot")
        val value = if (textSlot.isNotEmpty()) slots[textSlot] ?: st.optString("text") else st.optString("text")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 10000) {
            svc.settle(300, 3000)
            val snap = svc.snapshot()
            guardScreen(snap, app)?.let { return it }
            val field = snap.appNodes(app).firstOrNull { it.editable && it.focused }
                ?: Resolver.resolve(st, slots, snap, app)?.node
            if (field != null) {
                if (!Actions.setText(field, value)) return StepResult(false, "failed", "type", "couldn't type into the field")
                if (st.optBoolean("submit")) {
                    delay(500)
                    if (!Actions.imeEnter(field)) return StepResult(false, "failed", "type", "couldn't press search")
                }
                svc.settle(500, 4000)
                return StepResult(true, "success", "type", "\"$value\"")
            }
            delay(400)
        }
        return StepResult(false, "failed", "type", "no text field to type \"$value\" into")
    }
}
