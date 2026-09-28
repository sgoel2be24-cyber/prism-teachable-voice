package com.prism.tva.llm

import com.prism.tva.core.Snapshot
import com.prism.tva.core.Text
import com.prism.tva.core.UiNode
import com.prism.tva.recipe.Generaliser
import org.json.JSONArray
import org.json.JSONObject

/**
 * The three places the LLM is used. Each returns null when the model is unavailable or unsure, and
 * the caller falls back to the deterministic path (or asks the user).
 */
object Brain {

    // ------------------------------------------------------------------ 1. understanding a command

    class CommandMatch(
        val flowId: String?,
        val slots: Map<String, String>,
        val missing: List<String>,
        val confidence: Double,
        val statusQuery: Boolean,
        val otherApp: String?,
    )

    private const val MATCH_SYS = """You map a user's spoken command to one of the phone automations they taught earlier.
Rules:
- Paraphrases and changed values map to the same flow ("get me a farmhouse from dominos" -> the pizza flow with item=farmhouse).
- Fill "slots" only with values the user actually said. Write numbers as digits ("two" -> "2"). Keep names as the user said them.
- If the user asks for something the flow has no slot for (a quantity, a delivery address like "to Work", a size), still put it in "slots" under a short lowercase name ("quantity", "address", "size").
- "missing": required slots of the chosen flow that the user did not give and that cannot be inferred.
- If the user names a different app than the flow's app but the same kind of task, still choose the flow and put that app's name in "app".
- "status_query": true when the user asks about a previous run ("did it work?", "did the last run succeed?").
- If no taught flow fits, "flow" must be null. Never guess a flow for an unrelated request.
Reply with JSON only: {"flow": id|null, "slots": {name: value}, "missing": [names], "app": name|null, "confidence": 0..1, "status_query": true|false}"""

    suspend fun matchCommand(utterance: String, recipes: List<JSONObject>): CommandMatch? {
        val flows = JSONArray()
        for (r in recipes) {
            val slots = JSONArray()
            val arr = r.optJSONArray("slots") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                slots.put(JSONObject().put("name", s.getString("name")).put("required", s.optBoolean("required", true))
                    .put("meaning", s.optString("meaning")).put("taught_value", s.optString("example")))
            }
            flows.put(JSONObject().put("id", r.getString("id")).put("app", r.optString("appLabel"))
                .put("description", r.optString("description").ifEmpty { r.optString("template") })
                .put("taught_command", r.optString("command")).put("slots", slots))
        }
        val req = JSONObject().put("command", utterance).put("flows", flows).toString()
        // The API occasionally stalls for 20 s+; a fresh request usually answers in about a second.
        val j = Fireworks.json(MATCH_SYS, req, "match", timeoutMs = 8000)
            ?: Fireworks.json(MATCH_SYS, req, "match-retry", timeoutMs = 10000)
            ?: return null
        val slots = HashMap<String, String>()
        j.optJSONObject("slots")?.let { o -> o.keys().forEach { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { slots[k] = it } } }
        val missing = j.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return CommandMatch(
            j.optString("flow").takeIf { it.isNotBlank() && it != "null" },
            slots, missing, j.optDouble("confidence", 0.0), j.optBoolean("status_query", false),
            j.optString("app").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    // ------------------------------------------------------------------ 2. acting on an unexpected screen

    class Decision(
        val action: String,          // tap | type | scroll_down | scroll_up | back | done | ask | fail
        val node: UiNode?,
        val text: String?,
        val question: String?,
        val completesStep: Boolean,
        val reason: String,
    )

    private const val ACT_SYS = """You operate an Android app on the user's behalf through its accessibility tree, one action at a time.
You get the overall task, the current goal, and the numbered elements on screen. Choose ONE action.
Rules:
- If a pop-up, dialog, banner, tooltip or sheet you did not expect covers the screen, dismiss it (Got it / Close / Not now / Skip / ✕ / Cancel) unless it is part of the goal. A sheet left over from before (a size picker, filters) is not part of the goal: close it with ✕, or tap the large unlabelled backdrop button behind it if back does nothing.
- A dialog that would throw away something the user already has (replace or clear a cart that holds items from another restaurant or store, discard unsaved changes) is not yours to decide: reply "ask" with ONE short question naming what would be lost, e.g. "Your cart has items from Pizza Hut. Replace them with this order?". Once the user has said yes (see "Already done this run"), tap the replace/confirm button.
- If the app says the task can't be done right now (the restaurant or store is closed or not accepting orders, the item is sold out or unavailable, delivery isn't available at this address), don't dismiss it and try again: reply "fail" with a short reason the user will hear, e.g. "Pizza Hut isn't accepting orders right now".
- If the goal's element is not on screen, scroll (scroll_down / scroll_up) or go back if you are on the wrong page.
- "Already done this run" says what each of your earlier actions did. Never repeat an action that changed nothing; try something else. A full-screen photo viewer or image gallery is a dead end: go back.
- If the goal is already satisfied on this screen (for example the item is already in the cart with the right quantity), reply "done" and set "element" to the element that proves it (the item's name in the cart list, an "Added to bag" or "item in your bag" message, the add button of this product now reading "Go to bag" / "Go to cart", a filled field). "Add" buttons on other, similar products further down do not mean this one still needs adding. Being on a cart or bag page is not proof by itself: the item from this run must be listed. On a menu or product list, the item's name, price or an "In your collection"/"saved" tag is not proof it is in the cart; its card must show a quantity stepper (− 1 +) instead of ADD, or a bar must say "1 item added" / "View cart". If nothing on screen proves it, do not reply done.
- "The first result" means the first result that is not an advert: skip cards marked "Sponsored", "Sponsored Ad", "Ad" or "Promoted" (scroll down if only adverts are visible).
- Buttons that repeat on every item show which item they belong to ("ADD" for "Margherita Pizza"). Pick the item whose name matches the user's value most exactly ("Margherita Pizza", not "Double Cheese Margherita Pizza" or a combo/meal deal), and never add a different item.
- The task was demonstrated once, possibly in a different app or on an older screen. Look for the equivalent element even if it is worded differently ("Add to bag" = "Add to cart", "Bag" = "Cart"). If the goal needs an intermediate screen (open the product page to find the add button, open a sheet), take that step yourself.
- Never tap anything that pays, places an order, or submits a login, password, OTP or PIN. Reply "ask" instead.
- Never choose a size, colour, variant or any other personal option yourself unless the user's values say which one, or there is only one option. Reply "ask" with ONE short question instead, e.g. "Which size?". If the user's choice is not available (greyed out, sold out), ask again naming what is available if you can tell. Options in a horizontal-list may continue off screen: scroll_right on that list (element = the list) before deciding a choice is missing.
- Also reply "ask" when you truly cannot proceed (logged out, an app language you cannot read, an error). Never ask the user to describe the screen.
- "completes_step": true only if your action itself achieves the current goal.
Reply with JSON only: {"action": "tap"|"type"|"scroll_down"|"scroll_up"|"scroll_right"|"scroll_left"|"back"|"done"|"ask"|"fail", "element": number|null, "text": string|null, "question": string|null, "completes_step": true|false, "reason": "short"}"""

    suspend fun decide(task: String, goal: String, history: List<String>, snap: Snapshot, pkg: String,
                       values: Map<String, String> = emptyMap(), deadEnds: List<String> = emptyList()): Decision? {
        val (screen, elements) = renderScreen(snap, pkg)
        val user = buildString {
            append("Task: ").append(task).append('\n')
            if (values.isNotEmpty()) append("User's values: ").append(values.entries.joinToString(", ") { "${it.key}=${it.value}" }).append('\n')
            append("Current goal: ").append(goal).append('\n')
            if (history.isNotEmpty()) append("Already done this run: ").append(history.takeLast(8).joinToString("; ")).append('\n')
            if (deadEnds.isNotEmpty()) append("Already tried on THIS screen and it did nothing (do not choose again): ").append(deadEnds.joinToString("; ")).append('\n')
            if (elements.isEmpty()) append("(No elements from the app are on screen: it may still be loading.)\n")
            append('\n').append(screen)
        }
        // A slow reply (the API occasionally takes 15-25 s) is dropped and the caller simply asks again.
        val j = Fireworks.json(ACT_SYS, user, "act", timeoutMs = 12000) ?: return null
        val action = j.optString("action")
        val idx = j.optInt("element", -1)
        val node = elements.getOrNull(idx - 1)
        if (action in setOf("tap", "type") && node == null) return null
        return Decision(action, node, j.optString("text").takeIf { it.isNotBlank() && it != "null" },
            j.optString("question").takeIf { it.isNotBlank() && it != "null" },
            j.optBoolean("completes_step", false), j.optString("reason"))
    }

    private val ID_LIKE = Regex("^[a-z0-9]+([_-][a-z0-9]+)+$|^[a-z]+([A-Z][a-z0-9]*)+$|^[a-z]+$")

    /** A developer tag rather than words a person reads (React Native apps expose test ids like "buy_button"). */
    fun idLike(s: String) = ID_LIKE.matches(s.trim())

    /**
     * What an element says on screen. When its accessibility label is a developer tag, the words
     * drawn inside it come first (Myntra's "Add to Bag" button is labelled "buy_button").
     */
    fun display(snap: Snapshot, n: UiNode, max: Int = 100): String {
        val own = n.label
        if (own.isNotEmpty() && !idLike(own)) return own.take(max)
        val inner = snap.labelsIn(n, 6).filter { it != own && !idLike(it) }.take(3)
        return when {
            inner.isEmpty() -> own
            own.isEmpty() -> inner.joinToString(" · ")
            else -> inner.joinToString(" · ") + " [tag $own]"
        }.take(max)
    }

    /** Numbered, compact view of the app's screen for the LLM, plus the element list it indexes into. */
    fun renderScreen(snap: Snapshot, pkg: String, limit: Int = 90): Pair<String, List<UiNode>> {
        val wins = snap.windows.indices.filter { snap.windows[it].pkg == pkg && !snap.windows[it].isIme }
        val out = ArrayList<UiNode>()
        val seen = HashSet<Int>()
        val covered = HashSet<Int>()
        for (n in snap.nodes) {
            if (n.window !in wins || !n.visible || n.bounds.width() <= 0 || n.bounds.height() <= 0) continue
            val actionable = n.clickable || n.editable || n.scrollable || n.longClickable
            if (actionable && seen.add(n.idx)) {
                out.add(n)
                snap.subtree(n).forEach { covered.add(it.idx) }
            }
        }
        for (n in snap.nodes) {
            if (n.window !in wins || !n.visible || n.label.isEmpty() || n.idx in covered) continue
            out.add(n)
        }
        val sorted = out.sortedWith(compareBy({ -snap.windows[it.window].layer }, { it.bounds.top }, { it.bounds.left })).take(limit)
        val sb = StringBuilder()
        val main = wins.minByOrNull { snap.windows[it].layer }
        sb.append("Screen: ").append(snap.activity?.substringAfterLast('.') ?: "").append('\n')
        if (wins.size > 1) sb.append("(An extra window such as a dialog or sheet is open; its elements are listed first.)\n")
        // Repeated short buttons ("ADD" on every dish, "Add to cart" on every product) are useless to
        // the model without the item they belong to, so name their card.
        val repeats = sorted.filter { it.clickable && display(snap, it, 30).length in 1..14 }
            .groupingBy { it.shortId + "|" + it.shortCls + "|" + Text.stable(display(snap, it, 30)) }.eachCount()
        sorted.forEachIndexed { i, n ->
            var label = display(snap, n)
            if (n.clickable && label.length in 1..14 &&
                (repeats[n.shortId + "|" + n.shortCls + "|" + Text.stable(label)] ?: 0) > 1) {
                snap.rowLabels(n, 6).firstOrNull { l -> l.count { it.isLetter() } >= 3 && !idLike(l) }
                    ?.let { label = "$label\" for \"${it.take(50)}" }
            }
            val role = when {
                n.editable -> "field"
                n.scrollable && !n.clickable ->
                    if (n.shortCls.contains("Horizontal") || n.bounds.width() > n.bounds.height() * 3) "horizontal-list" else "scroll-area"
                n.clickable || n.longClickable -> if (n.shortCls.contains("Check") || n.shortCls.contains("Radio")) "option" else "button"
                else -> "text"
            }
            sb.append('[').append(i + 1).append("] ").append(role)
            if (n.shortId.isNotEmpty()) sb.append(" id=").append(n.shortId)
            if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
            if (n.editable && !n.hint.isNullOrBlank()) sb.append(" hint=\"").append(Text.clean(n.hint)).append('"')
            if (n.checked) sb.append(" (checked)")
            if (n.selected) sb.append(" (selected)")
            if (n.focused && n.editable) sb.append(" (focused)")
            if (n.window != main) sb.append(" {dialog}")
            sb.append(" y=").append(n.bounds.centerY() * 100 / snap.screenH).append('%')
            sb.append('\n')
        }
        return sb.toString() to sorted
    }

    // ------------------------------------------------------------------ 3. understanding a demonstration

    private const val REFINE_SYS = """You turn one demonstration of a phone task into a reusable automation.
You get the user's spoken command, the app, the recorded steps and the parameters (slots) a heuristic found.
Return JSON only:
{
 "name": short imperative name with {slot} placeholders, e.g. "Order {item} from {restaurant} on Zomato",
 "rename": {old_slot_name: better_name},   // meaningful names: item, restaurant, product, query, city...
 "slot_meanings": {slot_name: "what it is, a short noun phrase, e.g. 'pizza to order'"},
 "slot_questions": {slot_name: "the short question to ask when the user didn't say it, e.g. 'Which restaurant should I order from?'"},
 "steps": [{"i": step_index, "intent": "what this step is for, with {slot} placeholders", "noise": true|false}],
 "goals": [{"slot": "quantity"|"address"|..., "default": "value used in the demo or ''", "before_step": step_index or -1 for after the last step, "instruction": "how to make it true, with {slot}"}]
}
Guidance:
- Mark a step noise ONLY if the task works without it: a mis-tap that was immediately undone, a tap on something unrelated to the command (e.g. opening another dish's photo after the requested item was added), or dismissing a pop-up (pop-ups are handled automatically at run time).
- Never mark as noise a step that moves the task forward: one that opens the next screen the demo continues on (compare "screen" with "next_step_screen"), opens a sheet or options for the requested item, or confirms it. Two taps that look alike (a search suggestion, then the restaurant in the results) are usually both needed. Opening the cart or bag at the end, or going on towards checkout/payment ("Continue", "View cart", "Proceed"), is part of the task.
- Add a "quantity" goal when the task adds an item to a cart (default "1"); place it before the step that confirms adding the item (e.g. "Add item"), or -1 if there is none.
- Add an "address" goal for food delivery (default ""); place it at -1 (in the cart, make sure the delivery address is {address}).
- Do not invent other goals."""

    suspend fun refine(recipe: JSONObject): JSONObject? {
        val steps = recipe.getJSONArray("steps")
        val ex = HashMap<String, String>()
        recipe.optJSONArray("slots")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { ex[it.getString("name")] = it.optString("example") } }
        val lines = JSONArray()
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val t = s.optJSONObject("target")
            val next = if (i + 1 < steps.length()) steps.getJSONObject(i + 1) else null
            lines.put(JSONObject().put("i", i).put("kind", s.optString("kind")).put("step", Generaliser.describe(s, ex))
                .put("slot", s.optString("textSlot").ifEmpty { s.optString("anchorSlot") })
                .put("element_id", t?.optString("id") ?: "")
                .put("element_text", t?.optJSONArray("ownLabels")?.let { a -> (0 until minOf(3, a.length())).joinToString(" · ") { a.getString(it) } } ?: "")
                .put("card_text", t?.optJSONArray("rowLabels")?.let { a -> (0 until minOf(4, a.length())).map { a.getString(it) } } ?: emptyList<String>())
                .put("screen", s.optString("activity").substringAfterLast('.'))
                .put("next_step_screen", next?.optString("activity")?.substringAfterLast('.') ?: "(end)"))
        }
        val user = JSONObject().put("command", recipe.optString("command")).put("app", recipe.optString("appLabel"))
            .put("slots", JSONObject(ex as Map<*, *>)).put("steps", lines).toString()
        // Reasoning tokens count against max_tokens: leave room so the JSON isn't cut off.
        return Fireworks.json(REFINE_SYS, user, "refine", effort = "medium", maxTokens = 3500, timeoutMs = 45000)
            ?: Fireworks.json(REFINE_SYS, user, "refine-retry", effort = "low", maxTokens = 3000, timeoutMs = 30000)
    }

    /** The model writes intents with either the old or the new slot names; make them all the new ones. */
    private fun renamed(text: String, rename: Map<String, String>): String {
        var x = text
        rename.forEach { (a, b) -> x = x.replace("{$a}", "{$b}") }
        return x
    }

    /** Applies a [refine] result onto a heuristic recipe (renames slots everywhere, adds intents and goals). */
    fun applyRefinement(recipe: JSONObject, r: JSONObject): JSONObject {
        val rename = HashMap<String, String>()
        r.optJSONObject("rename")?.let { o -> o.keys().forEach { k -> val v = o.optString(k).trim(); if (v.matches(Regex("[a-z][a-z0-9_]{0,20}"))) rename[k] = v } }
        fun rn(n: String) = rename[n] ?: n
        val slots = recipe.optJSONArray("slots") ?: JSONArray()
        val meanings = r.optJSONObject("slot_meanings")
        val questions = r.optJSONObject("slot_questions")
        val newSlots = JSONArray()
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i)
            val old = s.getString("name")
            val name = rn(old)
            // The model may key meanings/questions by either name.
            fun pick(o: JSONObject?) = o?.optString(name).orEmpty().ifEmpty { o?.optString(old).orEmpty() }
            newSlots.put(JSONObject().put("name", name).put("example", s.optString("example")).put("required", true)
                .put("meaning", pick(meanings)).put("question", pick(questions)))
        }
        var template = recipe.optString("template")
        rename.forEach { (a, b) -> template = template.replace("{$a}", "{$b}") }
        val steps = recipe.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            if (s.has("textSlot")) s.put("textSlot", rn(s.getString("textSlot")))
            if (s.has("anchorSlot")) s.put("anchorSlot", rn(s.getString("anchorSlot")))
        }
        r.optJSONArray("steps")?.let { a ->
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val i = o.optInt("i", -1)
                if (i !in 0 until steps.length()) continue
                val s = steps.getJSONObject(i)
                o.optString("intent").takeIf { it.isNotBlank() }?.let { s.put("intent", renamed(it, rename)) }
                if (o.optBoolean("noise") && s.optString("kind") != "launch") {
                    // Guard rails on the model: a step carrying the user's value, or one that led to
                    // the app screen the demo continued on, is part of the task.
                    val carriesValue = s.optString("textSlot").isNotEmpty() || s.optString("anchorSlot").isNotEmpty()
                    val nextAct = if (i + 1 < steps.length()) steps.getJSONObject(i + 1).optString("activity") else ""
                    val navigated = nextAct.isNotEmpty() && s.optString("activity").isNotEmpty() &&
                        nextAct != s.optString("activity") && !nextAct.startsWith("android.")
                    if (carriesValue || navigated) com.prism.tva.core.Dbg.log("REFINE noise refused for step $i (value=$carriesValue navigated=$navigated)")
                    else s.put("noise", true)
                }
            }
        }
        val goals = JSONArray()
        r.optJSONArray("goals")?.let { a ->
            for (k in 0 until a.length()) {
                val g = a.getJSONObject(k)
                val slot = g.optString("slot").trim()
                if (!slot.matches(Regex("[a-z][a-z0-9_]{0,20}"))) continue
                goals.put(JSONObject().put("slot", slot).put("default", g.optString("default"))
                    .put("before", g.optInt("before_step", -1)).put("instruction", renamed(g.optString("instruction"), rename)))
                if ((0 until newSlots.length()).none { newSlots.getJSONObject(it).getString("name") == slot }) {
                    newSlots.put(JSONObject().put("name", slot).put("example", g.optString("default"))
                        .put("required", false).put("meaning", meanings?.optString(slot).orEmpty()))
                }
            }
        }
        return recipe.put("slots", newSlots).put("template", template).put("goals", goals)
            .put("description", r.optString("name").let { n -> var x = n; rename.forEach { (a, b) -> x = x.replace("{$a}", "{$b}") }; x })
            .put("source", "heuristic+llm")
    }
}
