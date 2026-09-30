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

    companion object {
        private const val ADDRESS_GOAL = "Set the delivery address to the saved address named \"{slot}\": tap the current " +
            "delivery location (usually at the top of the home screen), then pick \"{slot}\" from the saved addresses. " +
            "Don't edit or add an address."
        private val DISMISS = setOf("got it", "ok", "okay", "close", "not now", "skip", "cancel", "later", "maybe later",
            "no thanks", "dismiss", "x", "understood", "continue shopping")
        private val ADDISH = Regex("add to (cart|bag|basket)|^add( item)?\\b", RegexOption.IGNORE_CASE)
        private val PRICE = Regex("[₹$€£]\\s?\\d")
        private val CART_COUNT = listOf(
            Regex("^(?:cart|bag|basket)\\W+(\\d+) items?\\b", RegexOption.IGNORE_CASE),
            Regex("^(\\d+) items? (?:added|in (?:your )?(?:cart|bag))", RegexOption.IGNORE_CASE),
        )
        private val RETRY = setOf("try again", "retry", "tap to retry", "reload", "refresh")
        private val APP_ERROR = Regex("went wrong|no internet|connection|couldn.?t load|unable to load|oops|error|offline",
            RegexOption.IGNORE_CASE)
        // The shop can't take the order right now: say so instead of dismissing the notice and retrying.
        private val UNAVAILABLE = Regex("not accepting (any )?orders|currently closed|temporarily closed|restaurant is closed|" +
            "store is closed|delivery partners are (occupied|busy)|not delivering to|does ?n.?t deliver to|outside (the )?delivery area|" +
            "not serviceable|unserviceable", RegexOption.IGNORE_CASE)
    }

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
        val tapCounts: HashMap<String, Int> = HashMap(), // how often the LLM picked each action this run
        var alreadyHad: Boolean = false, // the last add step found the item already in the cart
        var next: JSONObject? = null, // the step after the current one, to recognise its screen
        var prev: JSONObject? = null, // the step before the current one
        var cartBefore: Int? = null, // cart count just before the last "Add to cart" press
        var addStep: Boolean = false, // the current step adds something to the cart/bag
        var lastTap: String = "", // what the model tapped last (to stop it unselecting a size)
        var cartSeen: Int? = null, // last cart count seen in this step (a size sheet hides the bag icon)
        var item: String = "", // the named item the last "ADD next to {item}" step was for
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
        slots.forEach { (k, v) -> if (v.isNotBlank()) s = s.replace("{$k}", v) }
        // An unknown value takes its little word with it: "Order pizza from {restaurant} on Zomato"
        // reads "Order pizza on Zomato", not "… from on Zomato".
        return s.replace(Regex("(?i)(\\b(from|on|in|at|to|for|with|of|a|an|the)\\s+)?\\{[a-z0-9_]+\\}"), "")
            .replace(Regex("\\s+"), " ").trim()
    }

    /** "Which size?" stays as it is; "no network" becomes "no network." */
    private fun endSentence(s: String): String = s.trim().let { if (it.isEmpty() || it.last() in ".?!") it else "$it." }

    private fun stepText(st: JSONObject, c: Ctx): String {
        val intent = Brain.safeIntent(st.optString("intent"))
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
        // Delivery address/location: pick it on the home screen right after opening the app (the
        // restaurant list depends on it), with wording that worked on Zomato.
        for (g in 0 until goals.length()) {
            val goal = goals.getJSONObject(g)
            val k = goal.optString("slot")
            if (Regex("address|location").containsMatchIn(k) && steps.length() > 1) {
                goal.put("before", 1).put("instruction", ADDRESS_GOAL.replace("{slot}", "{$k}"))
            }
        }
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
                        .put("instruction", if (place) ADDRESS_GOAL.replace("{slot}", "{$k}")
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
                c.next = (i + 1 until steps.length()).map { steps.getJSONObject(it) }.firstOrNull { !it.optBoolean("noise") }
                c.prev = (i - 1 downTo 0).map { steps.getJSONObject(it) }.firstOrNull { !it.optBoolean("noise") }
                val r = runStep(st, c)
                record(i, desc, r, System.currentTimeMillis() - s0)
                if (!r.ok) { outcome = r.outcome; reason = r.note; stoppedAt = i; break }
                c.history.add(short)
            }
            // The demo ended on the payment page: say so and hand over, rather than just "done".
            if (outcome == "success") {
                // The cart page can take a moment to load its payment bar.
                val until = System.currentTimeMillis() + 4000
                do {
                    svc.settle(600, 3000)
                    val end = svc.snapshot()
                    if (Guard.atPaymentStep(end, c.app)) {
                        // On the payment page itself, or on a cart/confirmation with a pay button showing.
                        outcome = "handover"; reason = if (Guard.screenBlock(end, c.app) == "payment") "payment" else "checkout"; break
                    }
                } while (System.currentTimeMillis() < until)
            }
        } catch (e: CancellationException) {
            outcome = "stopped"; reason = "stopped by the user"
        } catch (e: Exception) {
            outcome = "failed"; reason = e.toString()
            Dbg.log("RUN_ERROR $e")
        }
        // Values asked for mid-run are known now: name the task with them ("Add margherita pizza…").
        var done = fill(recipe.optString("description").ifEmpty { recipe.optString("name") }, slots).ifEmpty { c.task }
        // Run in a different app than the one taught: name the app it actually ran in.
        val taughtApp = recipe.optString("app")
        if (app != taughtApp) {
            val was = Actions.appLabel(svc, taughtApp)
            val now = Actions.appLabel(svc, app)
            // "Amazon Shopping" is usually just "Amazon" in the description.
            listOf(was, was.substringBefore(' ')).filter { it.length >= 4 && it != taughtApp }.firstOrNull { w ->
                Regex("\\b${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(done)
            }?.let { w -> done = done.replace(Regex("\\b${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE), now) }
        }
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
                "handover" -> when (reason) {
                    "payment" -> "Done: $done. I've stopped at the payment page. Your turn."
                    "checkout" -> "Done: $done. I've stopped at checkout, before paying. Your turn."
                    else -> "I've stopped at $reason. Your turn."
                }
                "stopped" -> "Stopped."
                "asked" -> "I've paused: ${endSentence(reason)}"
                else -> "I couldn't finish: ${endSentence(reason)}"
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
        // Recipes learned before the wording fix may say "should you"; the assistant is the one acting.
        val spoken = q?.takeIf { it.isNotBlank() }?.replace(Regex("\\b(should|shall|can|could) you\\b", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]} I" }
        return svc.asker.ask(spoken ?: "Which $slot should I use?")
    }

    private suspend fun runStep(st: JSONObject, c: Ctx): StepResult =
        when (st.optString("kind")) {
            "launch" -> launch(c.app)
            "back" -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                svc.settle(400, 3000)
                StepResult(true, "success", "global", "")
            }
            "tap", "longpress" -> tap(st, c).also { r ->
                // The model finished an add step without pressing anything: it found the item already
                // in the cart. Then the next step's options sheet ("Add item") won't appear either.
                if (st.optString("anchorSlot").isNotEmpty() && r.method == "llm-done" && c.llmTapped.isEmpty()) c.alreadyHad = true
                // The model finished an add step (the bag count went up, or it showed "in your bag"):
                // a following "confirm adding" step whose button isn't there has nothing left to do.
                if (c.addStep && r.ok && r.method in setOf("llm-done", "llm-added")) c.alreadyHad = true
            }
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
        c.tapCounts.clear()
        val skipIfMissing = c.alreadyHad && st.optString("anchorSlot").isEmpty()
        c.alreadyHad = false
        var start = System.currentTimeMillis()
        var scrolls = 0
        var preScrolls = 0
        var pageSearched = false
        var openedFirst = false // opened the first (non-advert) result ourselves
        var adScrolls = 0
        var adsSeen = 0
        var llmActs = 0
        var method = "match"
        var checks = 0 // times we re-checked an LLM "this finishes the step" claim on the resulting screen
        var verifying = false
        var startSig: Int? = null
        var retries = 0
        var llmFrom = 0L // when the model first looked at this step
        // The previous step pressed "Add to cart": if the cart count has gone up since, a following
        // "confirm" step (the product page's own add button) has nothing left to do.
        val cartBefore = c.cartBefore
        c.cartBefore = null
        c.addStep = st.optJSONObject("target")?.let { t -> ADDISH.containsMatchIn(t.optString("leafLabel").ifEmpty { t.optString("label") }) } == true ||
            Regex("\\badd\\b.*\\b(cart|bag|basket)\\b", RegexOption.IGNORE_CASE).containsMatchIn(st.optString("intent"))
        c.cartSeen = null
        if (c.addStep) st.optString("anchorSlot").takeIf { it.isNotEmpty() }?.let { c.slots[it] }
            ?.takeIf { it.isNotBlank() }?.let { c.item = it }
        var wrongSheets = 0
        // The demo's own button for this step was a payment one ("Add Payment Method", "Place order"):
        // stop here, whatever language the app shows now.
        st.optJSONObject("target")?.let { t ->
            val demo = listOf(t.optString("leafLabel"), t.optString("label")).filter { it.isNotBlank() }
            Guard.actionBlock(demo)?.let { r -> return StepResult(false, "handover", "guard", "${Guard.spoken(r)} (\"${demo.first()}\")") }
        }
        suggestionAsSearch(st, c)?.let { return it }
        while (true) {
            svc.settle(400, 2500) // shopping pages never go fully quiet (autoplaying carousels)
            val snap = svc.snapshot()
            guardScreen(snap, c.app)?.let { return it }
            if (!verifying) scriptMismatch(snap, st, c.app)?.let { return it }
            if (startSig == null) startSig = sig(snap, c.app)
            if (verifying) {
                // Let a loading screen finish before judging the result.
                if (blank(snap, c.app) && System.currentTimeMillis() - start < 45000) { delay(700); continue }
                unavailable(snap, c.app)?.let { return it }
                // The screen moved on and the next step's button is plainly there: this step did its
                // job. (Asked instead, the model tends to carry on with the following steps itself.)
                val nx = c.next
                // The next step types: a focused text box on a new screen means this one opened it.
                if (nx != null && nx.optString("kind") == "type" && sig(snap, c.app) != startSig &&
                    snap.appNodes(c.app).any { it.editable && it.focused }) {
                    Dbg.log("VERIFY the text box for the next step is open")
                    return StepResult(true, "success", "llm", "the text box is open")
                }
                // (Not when this step's own button is still there too: "Tap dominos" then "Tap dominos".)
                if (nx != null && nx.optString("kind") in setOf("tap", "longpress") && sig(snap, c.app) != startSig) {
                    val m = Resolver.resolve(nx, c.slots, snap, c.app)
                    if (m != null && m.score >= 5.0 && Resolver.resolve(st, c.slots, snap, c.app) == null) {
                        Dbg.log("VERIFY next step's target is on screen (%.1f)".format(m.score))
                        return StepResult(true, "success", "llm", "the next step's screen is showing")
                    }
                }
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
            // The item is already in the cart (left from the teaching demo or an earlier run): its card
            // shows "− 1 +" instead of ADD. Enough of it there already means the order is as asked.
            // Checked before matching, or an ADD on a look-alike ("Double Cheese Margherita", a
            // combo) could be taken instead.
            val anchorNow = c.slots[st.optString("anchorSlot")].orEmpty()
            if (anchorNow.isNotEmpty() && !c.cross) {
                val have = quantityShown(snap, anchorNow, c.app)
                val want = c.slots["quantity"]?.trim()?.toIntOrNull() ?: 1
                if (have != null && have >= want) {
                    Dbg.log("ALREADY_IN_CART \"$anchorNow\" x$have")
                    c.alreadyHad = true
                    c.history.add("\"$anchorNow\" was already in the cart ($have); nothing added")
                    return StepResult(true, "success", "skip", "already in the cart ($have)")
                }
            }
            // Sponsored results are skipped while we can still scroll past them (Amazon's earbuds
            // page opens with 5+ screens of ads).
            var m = Resolver.resolve(st, c.slots, snap, c.app, skipAds = scrolls < 10)
            // A pop-up window (a promo, a dialog) sits over the screen the button is on. An accessibility
            // click would reach the button underneath and carry on with the pop-up still open: let the
            // model deal with the pop-up first, and don't scroll the screen under it.
            if (m != null) {
                val top = snap.windows.indexOfFirst { it.isApp && it.pkg == c.app }
                val tb = snap.windows.getOrNull(top)?.bounds
                if (top >= 0 && m.node.window != top && tb != null &&
                    tb.width().toLong() * tb.height() >= snap.screenW.toLong() * snap.screenH * 15 / 100) {
                    Dbg.log("POPUP window over the screen; not tapping \"${Brain.display(snap, m.node, 40)}\" underneath")
                    m = null
                    preScrolls = 10
                }
            }
            if (m != null) {
                guardTap(snap, m.node)?.let { return it }
                val long = st.optString("kind") == "longpress"
                val shown = Brain.display(snap, m.node, 40).ifEmpty { m.node.label }
                // An options sheet for a different dish ("Spicy Sweet Corn" when the order is
                // Margherita): close it instead of adding the wrong thing, and find the right ADD.
                val wrongTitle = if (ADDISH.containsMatchIn(shown) && c.item.isNotEmpty()) otherItem(snap, m.node, c.item) else null
                if (wrongTitle != null) {
                    wrongSheets++
                    Dbg.log("WRONG_ITEM the options are for \"$wrongTitle\", not \"${c.item}\"")
                    if (wrongSheets > 2) return StepResult(false, "failed", "guard",
                        "the options that opened were for \"$wrongTitle\", not ${c.item}, so I didn't add it")
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    svc.settle(500, 3000)
                    c.history.add("the options sheet that opened was for \"$wrongTitle\", not \"${c.item}\"; closed it without adding. Tap ADD next to \"${c.item}\" itself")
                    preScrolls = 10 // straight to the model: the demo's screen isn't there any more
                    continue
                }
                if (ADDISH.containsMatchIn(shown)) c.cartBefore = cartCount(snap, c.app) ?: 0
                Dbg.log("TAP \"$shown\" card: ${snap.rowLabels(m.node, 4).joinToString(" | ") { it.take(60) }}${if (Resolver.sponsored(snap, m.node)) " [SPONSORED]" else ""}")
                var ok = press(m.node, long)
                svc.settle(350, 3000)
                // Zomato ignores accessibility clicks on some views (its "Continue" bar, the
                // location bar). If nothing at all changed, tap it for real, like a finger.
                if (!long && same(snap, svc.snapshot(), c.app)) {
                    val pt = snap.tapPoint(m.node)
                    if (pt != null) {
                        val (cx, cy) = pt
                        Dbg.log("TAP no visible effect; tapping for real")
                        ok = throughHud { Actions.tap(svc, cx, cy, false) } || ok
                        svc.settle(350, 3000)
                    }
                }
                if (scrolls > 0 && method == "match") method = "scroll+match"
                return StepResult(ok, if (ok) "success" else "failed", method,
                    "score=%.1f next=%.1f %s".format(m.score, m.runnerUp, m.why))
            }
            val elapsed = System.currentTimeMillis() - start
            unavailable(snap, c.app)?.let { return it }
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
            // The app's own network error ("Something went wrong · Try Again"): retry like a person
            // would, a few times, before spending time on scrolling or the model.
            if (retries < 3 && snap.appNodes(c.app).any { APP_ERROR.containsMatchIn(it.label) }) {
                val retry = snap.appNodes(c.app).firstOrNull { n ->
                    Text.norm(n.label) in RETRY && snap.actionable(n).let { it.clickable && it.visible }
                }
                if (retry != null) {
                    retries++
                    Dbg.log("APP_ERROR tapping \"${retry.label}\" ($retries)")
                    c.history.add("the app showed an error; tapped \"${retry.label}\"")
                    press(snap.actionable(retry))
                    svc.settle(800, 5000)
                    start = System.currentTimeMillis()
                    continue
                }
            }
            // A pop-up the demo dismissed ("Movie voucher unlocked · Got it") often doesn't come back.
            // Give it a moment to appear; if it hasn't and the next step's button is there, move on.
            if (llmActs == 0 && isDismissal(st)) {
                if (elapsed < 4000) { delay(400); continue }
                val nx = c.next
                if (nx == null || nx.optString("kind") !in setOf("tap", "longpress") || Resolver.resolve(nx, c.slots, snap, c.app) != null) {
                    return StepResult(true, "success", "skip", "the pop-up didn't appear this time")
                }
            }
            // The item was already in the cart, so the step confirming the add (the options sheet's
            // "Add item") has nothing to do.
            if (skipIfMissing && llmActs == 0) return StepResult(true, "success", "skip", "nothing to confirm: the item was already in the cart")
            // "Add to cart" on the results page put it straight in (no options page this time): the
            // demo's second add button, on the product page, isn't needed.
            if (cartBefore != null && llmActs == 0) {
                val now = cartCount(snap, c.app)
                if (now != null && now > cartBefore) {
                    c.history.add("the item went straight into the cart (cart $cartBefore → $now)")
                    return StepResult(true, "success", "skip", "the item went straight into the cart ($cartBefore → $now)")
                }
            }
            // Cheap before clever: the element is often just below the fold (a sponsored banner
            // pushed the first result down). Up to five thumb scrolls with the fast matcher, then the LLM.
            // Only adverts match so far ("Add to cart" on sponsored results): keep scrolling past them
            // with the fast matcher (up to 10 screens) rather than handing a page of ads to the model.
            val adsOnly = scrolls < 10 && Resolver.resolve(st, c.slots, snap, c.app, skipAds = false) != null
            if (preScrolls < (if (adsOnly) 10 else if (st.optInt("scrollsBefore") >= 8) 1 else 5) && !c.cross) {
                val s0 = sig(snap, c.app)
                if (adsOnly) Dbg.log("PRESCROLL ${preScrolls + 1}: only sponsored results on screen")
                scrollOnce(snap, c.app, forward = true)
                preScrolls++
                // A web page's tree can lag the scroll by a moment: look twice before deciding the
                // screen doesn't scroll.
                var moved = sig(svc.snapshot(), c.app) != s0
                if (!moved) { delay(900); moved = sig(svc.snapshot(), c.app) != s0 }
                if (!moved) preScrolls = 10 else scrolls++ // screen doesn't scroll
                continue
            }
            // An item far down a long menu: use the page's own "Search in …" box once, then match again.
            val anchorValue = c.slots[st.optString("anchorSlot")].orEmpty()
            // Also on a menu the model has just opened (another restaurant's, reached via its outlet
            // picker): search it for the item rather than letting the model guess which ADD is whose.
            val menuLike = llmActs > 0 && snap.appNodes(c.app).any { Text.norm(it.label) == "add" }
            // A screen without a search box (the outlet picker in front of the menu) doesn't use up the try.
            if ((st.optInt("scrollsBefore") >= 8 || menuLike) && !pageSearched && anchorValue.isNotEmpty() && !c.cross) {
                val searched = searchInPage(snap, anchorValue, c)
                if (searched != null) pageSearched = true
                if (searched == true) continue
            }
            // In another app, "add the first result" on a results grid whose cards have no add button
            // (Myntra): open the first result that isn't an advert ourselves, then the model adds it
            // from its page. Left alone, the model tapped the search text or scrolled for a button.
            if (c.cross && c.addStep && !openedFirst && llmActs == 0 &&
                Regex("\\b(first|top)\\b", RegexOption.IGNORE_CASE).containsMatchIn(st.optString("intent"))) {
                val (card, ads) = firstResultCard(snap, c.app)
                if (card != null) {
                    openedFirst = true
                    guardTap(snap, card)?.let { return it }
                    // Its words, not the app's test ids ("touchable_info").
                    val name = snap.labelsIn(card, 8).filterNot { Regex("^[A-Za-z0-9]+(_[A-Za-z0-9]+)+$").matches(it) }
                        .take(3).joinToString(" · ").take(70)
                    val (x, y) = snap.tapPoint(card)!!
                    Dbg.log("OPEN the first result that isn't an advert (${adsSeen + ads} adverts skipped): $name")
                    val s0 = sig(snap, c.app)
                    throughHud { Actions.tap(svc, x, y, false) }
                    // Wait for the product page itself, not the list mid-transition.
                    val until = System.currentTimeMillis() + 5000
                    while (System.currentTimeMillis() < until && sig(svc.snapshot(), c.app) == s0) delay(300)
                    svc.settle(700, 4000)
                    c.history.add("opened the first result that isn't an advert (\"$name\"); add this one from its page")
                    continue
                }
                if (ads > 0 && adScrolls < 3) {
                    adScrolls++
                    adsSeen += ads
                    Dbg.log("OPEN only adverts in view ($ads); scrolling")
                    scrollOnce(snap, c.app, forward = true)
                    continue
                }
            }
            // Fast path found nothing clear: let the LLM look at the screen. A stuck step is reported
            // within 30 s rather than guessing on (e.g. an app switched to another language).
            // In another app every screen is new, and an item far down a long list (the demo scrolled a
            // lot) may need the page's own search: both get a bigger budget.
            val far = st.optInt("scrollsBefore") >= 8
            val big = c.cross || far
            // Scrolling a web page (Amazon's results) can eat the whole budget before the model has
            // looked once: it always gets a first look and ~15 s of its own.
            val llmElapsed = if (llmFrom == 0L) 0L else System.currentTimeMillis() - llmFrom
            if (Fireworks.available && llmActs < (if (big) 12 else 8) &&
                (llmActs == 0 || elapsed < (if (big) 45000 else 26000) || llmElapsed < 15000)) {
                if (llmFrom == 0L) llmFrom = System.currentTimeMillis()
                llmActs++
                method = "llm"
                val t = System.currentTimeMillis()
                var hint = if (far) " In the demonstration the user scrolled a long way down to find it; if this screen has its own search box (e.g. \"Search in …\"), search for it there instead of scrolling." else ""
                // A cart left over from an earlier run (or the teaching demo itself) may already hold
                // the item: then the order is already as asked, and adding another would double it.
                if (anchorValue.isNotEmpty()) {
                    val qty = c.slots["quantity"]?.takeIf { it.isNotBlank() } ?: "1"
                    hint += " If \"$anchorValue\" is already in the cart with at least $qty (its card shows − $qty + instead of ADD), reply done pointing at that quantity; don't add another."
                }
                val r = llmAct(stepText(st, c) + demoHint(st) + hint, c, snap) ?: continue
                if (r.first.method == "ask") { start += System.currentTimeMillis() - t; llmFrom += System.currentTimeMillis() - t } // waiting for the user doesn't count
                if (r.second && r.first.method == "llm" && r.first.ok) { verifying = true; checks++; continue }
                if (r.second) return r.first // done, handed over, asked, or failed
                continue
            }
            val overall = if (llmActs > 0) (if (big) 50000 else 30000) else 15000
            if ((elapsed > overall && (llmActs == 0 || System.currentTimeMillis() - llmFrom > 20000)) || scrolls >= 12) {
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
        // The same tap over and over (on a screen that keeps changing on its own) is a loop too.
        val tries = c.tapCounts.merge(key, 1, Int::plus) ?: 1
        if (d.action != "done" && d.action != "ask" && tries > 3) dead.add(key)
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
                // A size just chosen may not report itself as selected (Myntra), so the model taps it
                // again and unselects it. Once is enough: the next move is the sheet's DONE / add button.
                val shownNow = Brain.display(snap, n, 50)
                if (c.lastTap.isNotEmpty() && Text.stable(shownNow) == c.lastTap &&
                    Regex("size|^(\\d{1,2}(\\.5)?|xs|s|m|l|xl|xxl|xxxl|free size|onesize|done|add to bag|add to cart)\\b", RegexOption.IGNORE_CASE).containsMatchIn(shownNow)) {
                    c.history.add("\"$shownNow\" was just pressed (pressing it again would unselect the option or add a second one); look for what it did, e.g. the bag count or a DONE / Go to bag button")
                    Dbg.log("LLM repeat option tap blocked: $shownNow")
                    return StepResult(true, "success", "llm", "option already chosen") to false
                }
                // While picking "the first result", an advert tile is never the one (Myntra marks them "AD").
                if (c.addStep && Resolver.sponsored(snap, n)) {
                    c.history.add("\"${shownNow.take(50)}\" is an advert (AD / Sponsored); the first result means the first one that isn't an advert")
                    Dbg.log("LLM tap on an advert blocked: $shownNow")
                    return StepResult(true, "success", "llm", "skipped an advert") to false
                }
                if (c.item.isNotEmpty() && ADDISH.containsMatchIn(shownNow)) {
                    otherItem(snap, n, c.item)?.let { title ->
                        c.history.add("\"${shownNow.take(40)}\" would add \"$title\", not \"${c.item}\": close this sheet (back or ✕) and tap ADD next to \"${c.item}\" itself")
                        Dbg.log("WRONG_ITEM the model's add is for \"$title\", not \"${c.item}\"; blocked")
                        return StepResult(true, "success", "llm", "blocked adding the wrong item") to false
                    }
                }
                c.lastTap = Text.stable(shownNow)
                // Tap like a finger where the model pointed: some apps accept an accessibility click
                // and ignore it (Zomato's location bar). Off-screen or under the keyboard: click.
                // The spot is the free part of the element: a sticky header can cover its centre.
                val pt = snap.tapPoint(n)
                val (cx, cy) = pt ?: (0f to 0f)
                val reachable = pt != null
                if (pt == null && !snap.actionable(n).clickable) {
                    c.history.add("\"${shownNow.take(50)}\" is hidden under the page's header, the keyboard or the screen edge; scroll a little to bring it into view, or pick one that's in view")
                    Dbg.log("TAP target covered: $shownNow")
                    return StepResult(true, "success", "llm", "target covered") to false
                }
                Dbg.log("TAP llm #${n.idx} ${n.shortCls} [${n.bounds.toShortString()}] at ${pt?.let { "(${it.first.toInt()},${it.second.toInt()})" } ?: "none"} clickable=${n.clickable} ad=${if (c.addStep) Resolver.sponsored(snap, n) else "-"}")
                // In an add step every tap is watched: on Myntra the item goes in on the size sheet's
                // "DONE", not on "Add to Bag".
                val labelAddish = ADDISH.containsMatchIn(Brain.display(snap, n, 50).ifEmpty { n.label }) ||
                    Regex("^(done|confirm|add item|add to bag)\\b", RegexOption.IGNORE_CASE).containsMatchIn(Brain.display(snap, n, 50))
                val addish = labelAddish || c.addStep
                // A size sheet covers the bag icon: compare with the count seen before it opened.
                val cartThen = if (addish) (cartCount(snap, c.app)?.also { c.cartSeen = it } ?: c.cartSeen) else null
                if (addish && c.cartBefore == null) c.cartBefore = cartThen ?: 0
                var ok = reachable && throughHud { Actions.tap(svc, cx, cy, false) }
                if (!ok) ok = press(if (pt == null) snap.actionable(n) else n)
                svc.settle(400, 3000)
                val what = Brain.display(snap, n, 50)
                // One "Add to cart" that raised the cart count is the whole job: never let the model
                // go on to add a second product (it once added three before noticing).
                if (addish && ok && cartThen != null) {
                    var now: Int? = null
                    for (i in 0 until (if (labelAddish) 6 else 1)) { now = cartCount(svc.snapshot(), c.app); if (now != null && now > cartThen) break; delay(500) }
                    if (now != null && now < cartThen) c.cartSeen = now
                    if (now != null && now > cartThen) {
                        c.llmTapped.add(Text.stable(what))
                        c.history.add("tapped \"$what\" → added to the cart (cart $cartThen → $now)")
                        Dbg.log("LLM add raised the cart $cartThen → $now")
                        // Its own method, so the step ends here instead of being "verified" by more taps.
                        return StepResult(true, "success", "llm-added", "added to the cart ($cartThen → $now)") to true
                    }
                }
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
                // Text in a search box is what we typed, not evidence that anything happened — unless
                // the step was to open that box (the next step types into it).
                val boxWasTheGoal = c.next?.optString("kind") == "type"
                val proof = d.node?.takeIf { !it.editable || boxWasTheGoal }?.let { Brain.display(snap, it, 60).ifEmpty { "the text box" } }
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
                // "Are the search results displayed now?" — the user can't answer better than the screen
                // can. Give a slow page a moment and look again instead.
                if (Regex("\\b(displayed|showing|shown|loaded|loading|appear(ed|ing)?|on (the|your) screen|is the (page|screen)|what (do|can) you see)\\b",
                        RegexOption.IGNORE_CASE).containsMatchIn(d.question ?: "")) {
                    Dbg.log("LLM ask about the screen treated as a wait: ${d.question}")
                    delay(1500)
                    c.history.add("asked the user \"${d.question}\" — not asked: the elements list is the screen. Decide from it (wait, scroll or go on)")
                    return StepResult(true, "success", "llm", "waited instead of asking about the screen") to false
                }
                if (c.asks >= 2) return StepResult(false, "asked", "llm", d.question ?: d.reason) to true
                c.asks++
                val ans = svc.asker.ask(d.question ?: "I'm stuck here. What should I do?")
                    ?: return StepResult(false, "asked", "ask", d.question ?: d.reason) to true
                c.history.add("the user answered \"${d.question}\" with \"$ans\"")
                // "Replace your cart?" — "No": leave everything as it is and hand back.
                if (Regex("replace|clear|discard|remove|reset|lose", RegexOption.IGNORE_CASE).containsMatchIn(d.question ?: "") &&
                    Regex("^(no|nope|nah|don t|dont|do not|cancel|keep|leave|nahi|mat)\\b").containsMatchIn(Text.norm(ans))) {
                    return StepResult(false, "asked", "ask", "you chose to keep what's there, so I left it as it is") to true
                }
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
        c.tapCounts.clear()
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
     * ("Search in Domino's Pizza"), reached from the search bar at the top. Null when the page has
     * no search box; false when typing into it failed.
     */
    private suspend fun searchInPage(snap: Snapshot, value: String, c: Ctx): Boolean? {
        val inPage = Regex("^search (in|within) ", RegexOption.IGNORE_CASE)
        val notText = Regex("voice|scan|camera|mic|filter", RegexOption.IGNORE_CASE)
        val boxes = snap.appNodes(c.app).filter { n ->
            val words = Brain.display(snap, n, 80) + " " + Text.clean(n.hint) + " " + n.shortId
            (n.clickable || n.editable) && words.contains("search", ignoreCase = true) && !notText.containsMatchIn(words)
        }
        val box = boxes.firstOrNull { inPage.containsMatchIn(Brain.display(snap, it, 80).ifEmpty { Text.clean(it.hint) }) }
            ?: boxes.minByOrNull { it.bounds.top } ?: return null
        Dbg.log("PAGE_SEARCH \"$value\" via \"${Brain.display(snap, box, 40)}\"")
        if (!box.editable) { press(box); svc.settle(400, 3000) }
        val s2 = svc.snapshot()
        val field = s2.appNodes(c.app).firstOrNull { it.editable && it.focused }
            ?: s2.appNodes(c.app).firstOrNull { it.editable } ?: return false
        if (!Actions.setText(field, value)) return false
        svc.settle(700, 3000)
        c.history.add("searched this page for \"$value\"")
        // The keyboard stays up over the results: the first tap on a result only closes it, and the
        // item's ADD may sit under it. Back hides just the keyboard (the search stays open).
        if (svc.snapshot().imeTop() > 0) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            svc.settle(400, 2000)
            val kept = svc.snapshot().appNodes(c.app).any { it.editable && Text.norm(it.label) == Text.norm(value) }
            Dbg.log("PAGE_SEARCH keyboard hidden; query ${if (kept) "kept" else "lost"}")
            if (!kept) return false
        }
        return true
    }

    /**
     * The demo typed the start of a word and tapped the suggestion that was exactly the value
     * ("wirel" -> "wireless earbuds"). Now the whole value is typed; if no suggestion reads it word
     * for word, the search key does what that tap did (a near suggestion would search for
     * something else).
     */
    private suspend fun suggestionAsSearch(st: JSONObject, c: Ctx): StepResult? {
        val slot = st.optString("textSlot").ifEmpty { return null }
        val prev = c.prev ?: return null
        if (prev.optString("kind") != "type" || prev.optString("textSlot") != slot || prev.optBoolean("submit")) return null
        val t = st.optJSONObject("target") ?: return null
        if (t.optBoolean("editable")) return null
        val example = c.recipe.optJSONArray("slots")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ?.firstOrNull { it.optString("name") == slot }?.optString("example").orEmpty()
        val demo = t.optString("leafLabel").ifEmpty { t.optString("label") }
        if (example.isBlank() || Text.norm(demo) != Text.norm(example)) return null
        val value = c.slots[slot].orEmpty().ifBlank { return null }
        svc.settle(400, 2500)
        val snap = svc.snapshot()
        val nodes = snap.appNodes(c.app)
        if (nodes.any { it.visible && !it.editable && Text.norm(it.label) == Text.norm(value) }) return null
        val field = nodes.firstOrNull { it.editable && it.focused } ?: return null
        if (!Actions.imeEnter(field)) return null
        Dbg.log("SUGGESTION none reads \"$value\"; pressed the search key instead")
        svc.settle(500, 4000)
        return StepResult(true, "success", "search-key", "no suggestion reads \"$value\", so I pressed search")
    }

    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

    /**
     * The app now shows Hindi where the demo showed English (the judge switched the language): the
     * learned buttons can't be recognised, and guessing could tap the wrong thing. Say so at once.
     */
    private fun scriptMismatch(snap: Snapshot, st: JSONObject, app: String): StepResult? {
        val t = st.optJSONObject("target") ?: return null
        val demo = t.optString("leafLabel").ifEmpty { t.optString("label") }
        if (demo.isBlank() || DEVANAGARI.containsMatchIn(demo) || demo.count { it.isLetter() } < 3) return null
        val labels = snap.appNodes(app).map { it.label }.filter { l -> l.count { it.isLetter() } >= 2 }
        if (labels.size < 6) return null
        val hindi = labels.count { DEVANAGARI.containsMatchIn(it) }
        if (hindi * 10 < labels.size * 4) return null // under 40 % of the screen's text
        Dbg.log("SCRIPT_MISMATCH $hindi of ${labels.size} labels are Hindi; the demo's button was \"$demo\"")
        return StepResult(false, "failed", "language",
            "the app is showing Hindi now, but I learned this task in English, so I can't tell its buttons apart. " +
                "Switch the app back to English, or teach me the task again in Hindi")
    }

    private val GENERIC_ITEM_WORDS = setOf("pizza", "pizzas", "burger", "burgers", "meal", "meals", "combo", "large",
        "small", "medium", "regular", "with", "and", "the", "extra")

    /**
     * When nothing in the window around [n] (an add button) names [item], that window's title: the
     * button would add another dish. Zomato's options sheet is the only thing reported while it's
     * open, so it is judged on its own; a menu row is anchored to the item and always names it.
     * Null when the item is named.
     */
    private fun otherItem(snap: Snapshot, n: UiNode, item: String): String? {
        val words = Text.norm(item).split(' ').filter { it.length >= 3 }
        val keys = words.filter { it.length >= 4 && it !in GENERIC_ITEM_WORDS }.ifEmpty { words }
        if (keys.isEmpty()) return null
        val labels = snap.nodes.filter { it.window == n.window && it.visible && it.label.isNotEmpty() }
        val seen = labels.flatMap { Text.norm(it.label).split(' ') }.toSet()
        // Same word tolerance as the anchor match: "bread" is in Domino's "Garlic Breadsticks",
        // "margarita" is "Margherita".
        fun has(k: String) = k in seen || seen.any { w ->
            (k.length >= 4 && w.length >= 4 && (w.startsWith(k) || k.startsWith(w) || Text.sim(w, k) >= 0.8)) ||
                (k.length >= 6 && w.length >= 5 && Text.lev(w, k) <= 2)
        }
        if (keys.all { has(it) }) return null
        // The sheet's title: its first real words (not an icon glyph, a price or the button itself).
        return labels.map { it.label.trim() }.firstOrNull { l ->
            l.length in 4..70 && l.any { ch -> ch.isLetter() } && !ADDISH.containsMatchIn(l) &&
                !Regex("^(required|optional|select|choose)", RegexOption.IGNORE_CASE).containsMatchIn(l)
        } ?: "another item"
    }

    /**
     * The first product card on a results grid that isn't an advert, and how many advert cards came
     * before it. A product card is a clickable box with a price in it that repeats (at least two on
     * screen) and isn't the whole page. Only for grids without their own add buttons.
     */
    private fun firstResultCard(s: Snapshot, app: String): Pair<UiNode?, Int> {
        val nodes = s.appNodes(app)
        if (nodes.any { ADDISH.containsMatchIn(it.label) }) return null to 0
        val screen = s.screenW.toLong() * s.screenH
        fun area(n: UiNode) = n.bounds.width().toLong() * n.bounds.height()
        fun inside(n: UiNode, anc: UiNode): Boolean {
            var p = s.parentOf(n); var hops = 0
            while (p != null && hops++ < 40) { if (p.idx == anc.idx) return true; p = s.parentOf(p) }
            return false
        }
        val priced = nodes.filter { n ->
            n.clickable && n.bounds.width() > 0 && n.bounds.height() > 0 && area(n) in (screen / 80)..(screen / 3) &&
                s.subtree(n).any { it.visible && PRICE.containsMatchIn(it.label) }
        }
        val cards = priced.filter { a -> priced.none { b -> b !== a && inside(b, a) } }
        if (cards.size < 2) return null to 0
        // The whole card: the largest box around it that holds no other card (so the "AD" tag on its
        // picture is found too).
        fun whole(n: UiNode): UiNode {
            var cur = n
            while (true) {
                val p = s.parentOf(cur) ?: return cur
                if (area(p) > screen / 2 || cards.any { it !== n && inside(it, p) }) return cur
                cur = p
            }
        }
        val inView = cards.filter { it.bounds.top >= 0 && s.tapPoint(it) != null }
            .map { it to whole(it) }
            .sortedWith(compareBy({ it.second.bounds.top }, { it.second.bounds.left }))
        var ads = 0
        for ((card, box) in inView) {
            if (s.subtree(box).any { Resolver.isAd(it) }) { ads++; continue }
            return card to ads
        }
        return null to ads
    }

    /** Items in the cart as the app shows it: Amazon's "Cart 5 items" tab, Zomato's "1 item added" bar. */
    private fun cartCount(s: Snapshot, app: String): Int? {
        val nodes = s.appNodes(app)
        for (n in nodes) {
            val l = n.label.trim()
            CART_COUNT.firstNotNullOfOrNull { rx -> rx.find(l)?.groupValues?.get(1)?.toIntOrNull() }?.let { return it }
        }
        // An icon labelled "bag"/"cart" with a number badge beside it (Myntra: "bag" + "10").
        for (n in nodes) {
            if (Text.norm(n.label) !in setOf("bag", "cart", "basket", "shopping bag", "shopping cart")) continue
            // The badge sits somewhere in the icon's own small box (Myntra: bag's parent → badge box → "10").
            var box: UiNode? = s.parentOf(n)
            var hops = 0
            while (box != null && hops < 3 && box.bounds.width() < s.screenW / 4) {
                s.subtree(box).firstNotNullOfOrNull { x -> x.takeIf { it.idx != n.idx }?.label?.trim()?.toIntOrNull()?.takeIf { it in 0..999 } }?.let { return it }
                box = s.parentOf(box); hops++
            }
        }
        return null
    }

    /** The app says the shop can't take the order now ("Currently not accepting orders"): stop and say so. */
    private fun unavailable(snap: Snapshot, app: String): StepResult? {
        val n = snap.appNodes(app).firstOrNull { it.label.length < 200 && UNAVAILABLE.containsMatchIn(it.label) } ?: return null
        val said = n.label.replace(Regex("\\s+"), " ").trim().trimEnd('.')
        Dbg.log("UNAVAILABLE \"$said\"")
        return StepResult(false, "failed", "unavailable", "the app says \"$said\"")
    }

    /** A step that only closes a pop-up (its button is "Got it", "Not now", "✕"...), not part of the task. */
    private fun isDismissal(st: JSONObject): Boolean {
        if (st.optString("anchorSlot").isNotEmpty() || st.optString("textSlot").isNotEmpty()) return false
        val t = st.optJSONObject("target") ?: return false
        val label = Text.norm(t.optString("leafLabel").ifEmpty { t.optString("label") })
        return label in DISMISS || Regex("\\b(dismiss|close)\\b.*\\b(pop.?up|dialog|banner|sheet|tooltip)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(st.optString("intent"))
    }

    /**
     * How many of [item] the screen says are in the cart: a small number inside a − / + stepper in
     * the item's card (the card is found the same way as for anchors). Null if no such stepper.
     */
    private fun quantityShown(s: Snapshot, item: String, app: String): Int? {
        for (n in s.appNodes(app)) {
            val q = n.label.trim().toIntOrNull() ?: continue
            if (q !in 1..20) continue
            val p = s.parentOf(n) ?: continue
            val buttons = p.children.map { s.nodes[it] }.count { it.visible && it.clickable && it.idx != n.idx }
            if (buttons < 2) continue
            // Walk out from the "− 1 +" stepper to its dish card: the nearest box that names the item,
            // stopping once the box grows to hold another card (an ADD or a second stepper). The name
            // must start the label, so "Double Cheese Margherita" doesn't count as "margherita".
            val want = Text.norm(item)
            var cur: UiNode? = p
            var hops = 0
            while (cur != null && hops < 8 && cur.bounds.height() < s.screenH * 0.5) {
                val labels = s.labelsIn(cur, 40)
                if (hops > 0 && labels.any { Text.norm(it) == "add" }) break
                if (hops > 0 && s.subtree(cur).count { x -> x.idx != n.idx && x.label.trim().toIntOrNull() in 1..20 && (s.parentOf(x)?.children?.size ?: 0) >= 3 } > 0) break
                if (labels.any { val l = Text.norm(it); l.isNotEmpty() && (l == want || l.startsWith("$want ") || want.startsWith("$l ")) }) return q
                cur = s.parentOf(cur); hops++
            }
        }
        return null
    }

    /**
     * The page is still loading: nothing in the middle of the screen yet, or a loading screen with a
     * single line of text (Zomato's "Your best idea might just be a tea break" over shimmer boxes).
     */
    private fun blank(s: Snapshot, app: String): Boolean {
        val nodes = s.appNodes(app)
        if (nodes.isNotEmpty() && nodes.map { it.normLabel }.filter { it.isNotEmpty() }.distinct().size <= 2) return true
        val top = (s.screenH * 0.2).toInt()
        val bottom = (s.screenH * 0.85).toInt()
        // A spinner in the middle of an otherwise empty page (Zomato's suggestions while loading).
        if (nodes.any { it.shortCls == "ProgressBar" && it.bounds.centerY() in top..bottom } &&
            nodes.count { it.bounds.centerY() in top..bottom && it.label.isNotEmpty() } < 3) return true
        return nodes.count {
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
        // Checked/selected state counts too: a toggle that flipped must not be tapped again.
        fun labels(s: Snapshot) = s.appNodes(app).asSequence().filter { it.label.isNotEmpty() || it.checked || it.selected }
            .map { it.normLabel + "@" + it.bounds.top / 16 + (if (it.checked) "#c" else "") + (if (it.selected) "#s" else "") }
            .take(80).toSet()
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
        // With the keyboard up, a swipe across it glide-types a word into the search box ("by by by").
        // Swipe only in the part of the list above it, or scroll the list without a gesture.
        val ime = snap.imeTop()
        val lo = if (ime > 0) ime - snap.screenH * 0.04f else snap.screenH * 0.74f
        val hi = snap.screenH * 0.26f
        if (lo - hi < snap.screenH * 0.15f) {
            val ok = Resolver.mainScrollable(snap, app)?.let { Actions.scroll(it, forward) } ?: false
            svc.settle(350, 2000)
            return ok
        }
        val (y1, y2) = if (forward) lo to hi else hi to lo
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
