package com.prism.tva.recipe

import android.content.Context
import com.prism.tva.core.Actions
import com.prism.tva.core.Text
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns one teach recording plus the spoken command into a reusable recipe, without an LLM.
 *
 * Slots are found the way SUGILITE (Li et al., CHI 2017) does it: a word or phrase from the command
 * that reappears in something the user typed, tapped, or tapped next to becomes a parameter.
 * "Order a Margherita pizza from Domino's" + typed "dominos" + ADD tapped in the card that says
 * "Margherita Pizza"  ->  "order a {item} pizza from {query} ...".
 * The LLM pass (later) renames slots, writes step intents and flags noise; this pass is the fallback.
 */
object Generaliser {

    private val STOP = setOf(
        "a", "an", "the", "to", "from", "on", "in", "at", "of", "for", "and", "with", "me", "my", "please",
        "i", "want", "would", "like", "get", "order", "buy", "add", "search", "find", "open", "show", "cart",
        "app", "then", "it", "into", "first", "result", "results", "some", "can", "you", "via", "using", "go",
        "is", "this", "that", "up", "let", "lets", "now", "just",
        // Placeholder nouns ("add an item to Zomato") name no value, so they never become a slot.
        "item", "items", "thing", "things", "something", "anything", "product", "products", "stuff",
    )
    private val SYSTEM_PKGS = setOf("com.android.systemui")

    private class Span(val start: Int, val end: Int, val text: String)
    private class Slot(val name: String, val span: Span)

    fun build(rec: JSONObject, ctx: Context, homePkg: String?, ownPkg: String): JSONObject {
        val command = rec.getString("command")
        val arr = rec.getJSONArray("steps")
        val raw = (0 until arr.length()).map { arr.getJSONObject(it) }

        // 1. Which app was this taught on? The one most taps and typing happened in.
        val app = raw.filter { it.optString("kind") in setOf("tap", "longpress", "type") }
            .map { it.optString("pkg") }
            .filter { it.isNotEmpty() && it != homePkg && it != ownPkg && it !in SYSTEM_PKGS && !it.contains("inputmethod") }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
        val appLabel = if (app.isEmpty()) "" else Actions.appLabel(ctx, app)

        // 2. Keep the app's own steps; everything else (launcher, other apps, calls, shade) is noise.
        val kept = ArrayList<JSONObject>()
        val noise = JSONArray()
        var seenApp = false
        var lastPkg = ""
        var scrolls = 0 // scrolling since the last kept step: how far down the demo had to look
        for (s in raw) {
            val pkg = s.optString("pkg")
            when (s.optString("kind")) {
                "tap", "longpress", "type" -> {
                    // The 3-button navigation bar's Back (Samsung's default) is a tap in System UI, not a
                    // gesture: inside the app it is a back step, not noise.
                    val navBack = pkg in SYSTEM_PKGS && s.optString("kind") == "tap" &&
                        Text.norm(s.optJSONObject("target")?.optString("leafLabel").orEmpty().ifEmpty { s.optJSONObject("target")?.optString("label").orEmpty() }) == "back"
                    if (navBack) {
                        if (seenApp && lastPkg == app) kept.add(JSONObject().put("kind", "back"))
                        continue
                    }
                    when {
                        pkg == app -> {
                            seenApp = true
                            if (scrolls > 0) s.put("scrollsBefore", scrolls)
                            scrolls = 0
                            kept.add(s)
                        }
                        pkg == homePkg && !seenApp -> Unit // the launcher tap that opened the app
                        else -> noise.put(JSONObject().put("reason", "outside ${appLabel.ifEmpty { "the app" }}: $pkg")
                            .put("label", s.optJSONObject("target")?.optString("leafLabel") ?: ""))
                    }
                    if (pkg.isNotEmpty() && !pkg.contains("inputmethod")) lastPkg = pkg
                }
                // A back used to leave another app (a notification, a quick reply) is part of the detour.
                "back" -> if (seenApp && lastPkg == app) kept.add(JSONObject().put("kind", "back"))
                    else if (seenApp) { noise.put(JSONObject().put("reason", "back out of $lastPkg")); lastPkg = app }
                "scroll" -> if (seenApp && (pkg.isEmpty() || pkg == app)) scrolls++
                else -> Unit // home ends the flow
            }
        }
        while (kept.isNotEmpty() && kept.last().optString("kind") == "back") kept.removeAt(kept.size - 1)
        dedupe(kept)

        // 3. Slots.
        val appWords = (Text.tokens(appLabel) + Text.tokens(app.substringAfterLast('.'))).toSet()
        val cmdTokens = Text.tokens(command)
        val isContent = { t: String -> t !in STOP && t !in appWords }
        val spans = ArrayList<Span>()
        for (len in 1..4) for (i in 0..cmdTokens.size - len) {
            val sub = cmdTokens.subList(i, i + len)
            if (!isContent(sub.first()) || !isContent(sub.last())) continue
            spans.add(Span(i, i + len, sub.joinToString(" ")))
        }
        val slots = ArrayList<Slot>()
        fun slotFor(sp: Span, base: String): Slot {
            slots.firstOrNull { it.span.start == sp.start && it.span.end == sp.end }?.let { return it }
            var name = base
            var k = 2
            while (slots.any { it.name == name }) name = base + k++
            return Slot(name, sp).also { slots.add(it) }
        }
        fun overlapsSlot(sp: Span) = slots.any { sp.start < it.span.end && it.span.start < sp.end }

        val steps = JSONArray()
        steps.put(JSONObject().put("kind", "launch").put("pkg", app).put("label", appLabel)
            .put("desc", "Open $appLabel"))
        for (s in kept) {
            val kind = s.optString("kind")
            val out = JSONObject(s.toString())
            out.remove("screen"); out.remove("t")
            when (kind) {
                "type" -> {
                    val typed = s.optString("text")
                    val t = Text.norm(typed)
                    // People type the start of a name and tap a suggestion ("domi" → "Domino's Pizza"),
                    // or more than the name ("domino's pizza"). Both still mean the command's "dominos".
                    // A span across "from"/"on" ("margherita pizza from dominos") is never one value.
                    fun plain(sp: Span) = cmdTokens.subList(sp.start, sp.end).none { it in STOP }
                    fun score(sp: Span): Double = when {
                        sp.text == t -> 4.0
                        t.length >= 3 && plain(sp) && sp.text.startsWith(t) -> 3.0 + (sp.end - sp.start) * 0.1
                        plain(sp) && t.startsWith(sp.text + " ") -> 2.0 + (sp.end - sp.start) * 0.1
                        else -> Text.sim(sp.text, typed)
                    }
                    val best = spans.filter {
                        Text.fuzzyContains(typed, it.text) || Text.fuzzyContains(it.text, typed) || (t.length >= 3 && it.text.startsWith(t))
                    }.maxByOrNull { score(it) }
                    if (best != null && (score(best) >= 2.0 || Text.sim(best.text, typed) >= 0.6) && !s.optBoolean("secret")) {
                        out.put("textSlot", slotFor(best, "query").name)
                    }
                }
                "tap", "longpress" -> {
                    val t = s.optJSONObject("target") ?: JSONObject()
                    val own = listOf(t.optString("leafLabel"), t.optString("label")) + t.optJSONArray("ownLabels").strings()
                    val row = t.optJSONArray("rowLabels").strings()
                    val existing = slots.firstOrNull { sl -> own.any { Text.fuzzyContains(it, sl.span.text) } }
                    // Longest command phrase the element shows: "margherita pizza", not just "pizza".
                    // Among equally long ones, the one closest to the label: a "Domino's Pizza" tile
                    // is "dominos", not "pizza".
                    fun closeness(sp: Span) = own.filter { it.isNotBlank() }.maxOfOrNull { Text.sim(sp.text, it) } ?: 0.0
                    val fresh = existing ?: spans.filter { !overlapsSlot(it) && it.text.length >= 3 }
                        .filter { sp -> own.any { Text.fuzzyContains(it, sp.text) } }
                        .maxWithOrNull(compareBy<Span>({ it.end - it.start }, { closeness(it) }))
                        ?.let { slotFor(it, "choice") }
                    if (fresh != null) {
                        out.put("textSlot", fresh.name)
                    } else {
                        val anchorSlot = slots.firstOrNull { sl -> row.any { Text.fuzzyContains(it, sl.span.text) } }
                            ?: spans.filter { !overlapsSlot(it) && it.text.length >= 3 }
                                .sortedByDescending { it.end - it.start }
                                .firstOrNull { sp -> row.any { Text.fuzzyContains(it, sp.text) } }
                                ?.let { slotFor(it, "item") }
                        if (anchorSlot != null) {
                            out.put("anchorSlot", anchorSlot.name)
                            // A value the user searched for: results needn't repeat the words, so the
                            // card match only nudges. A value picked from a list must match.
                            val searched = (0 until steps.length()).any { k ->
                                steps.getJSONObject(k).let { it.optString("kind") == "type" && it.optString("textSlot") == anchorSlot.name }
                            }
                            if (searched) out.put("anchorSoft", true)
                        }
                        else if (row.isNotEmpty() && t.optString("leafLabel").length <= 14) {
                            // Short/generic button ("ADD", "+"): remember the card it sat in.
                            row.firstOrNull { l -> l.any { it.isLetter() } && Text.stable(l).length >= 3 }
                                ?.let { out.put("anchorText", it) }
                        }
                    }
                }
            }
            steps.put(out)
        }

        // 4. Template for matching later commands, and human-readable step descriptions.
        val template = cmdTokens.indices.joinToString(" ") { i ->
            val sl = slots.firstOrNull { i >= it.span.start && i < it.span.end }
            when {
                sl == null -> cmdTokens[i]
                i == sl.span.start -> "{${sl.name}}"
                else -> ""
            }
        }.replace(Regex("\\s+"), " ").trim()
        val slotArr = JSONArray()
        slots.forEach { slotArr.put(JSONObject().put("name", it.name).put("example", it.span.text)) }
        val examples = slots.associate { it.name to it.span.text }
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            if (!st.has("desc")) st.put("desc", describe(st, examples))
        }

        return JSONObject()
            .put("id", "r_" + System.currentTimeMillis())
            .put("name", Text.clean(command))
            .put("command", command)
            .put("template", template)
            .put("app", app).put("appLabel", appLabel)
            .put("slots", slotArr)
            .put("steps", steps)
            .put("noise", noise)
            .put("recording", rec.optString("id"))
            .put("createdAt", System.currentTimeMillis())
            .put("source", "heuristic")
    }

    /** Human-readable description of a recipe step, with slot values filled in. */
    fun describe(st: JSONObject, slots: Map<String, String>): String {
        val t = st.optJSONObject("target")
        fun v(name: String) = slots[name] ?: name
        return when (st.optString("kind")) {
            "launch" -> "Open ${st.optString("label")}"
            "back" -> "Go back"
            "type" -> {
                val text = if (st.has("textSlot")) v(st.getString("textSlot")) else st.optString("text")
                "Type \"$text\"" + if (st.optBoolean("submit")) " and search" else ""
            }
            "tap", "longpress" -> {
                val label = when {
                    st.has("textSlot") -> v(st.getString("textSlot"))
                    else -> t?.optString("leafLabel")?.ifEmpty { t.optString("id") } ?: "element"
                }
                val anchor = when {
                    st.has("anchorSlot") -> v(st.getString("anchorSlot"))
                    st.has("anchorText") -> st.getString("anchorText")
                    else -> ""
                }
                (if (st.optString("kind") == "longpress") "Long-press" else "Tap") + " \"$label\"" +
                    if (anchor.isNotEmpty()) " next to \"$anchor\"" else ""
            }
            else -> st.optString("kind")
        }
    }

    private fun dedupe(list: MutableList<JSONObject>) {
        var i = 1
        while (i < list.size) {
            val a = list[i - 1]
            val b = list[i]
            val same = a.optString("kind") == "tap" && b.optString("kind") == "tap" &&
                b.optLong("t") - a.optLong("t") < 400 &&
                a.optJSONObject("target")?.optString("leafLabel") == b.optJSONObject("target")?.optString("leafLabel")
            if (same) list.removeAt(i) else i++
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
}

/** Maps a new command onto a learned recipe (template match). The LLM matcher handles paraphrases. */
object Matcher {
    private val QTY_START = Regex("^(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|a couple of|a few)\\b", RegexOption.IGNORE_CASE)

    class Match(val recipe: JSONObject, val slots: Map<String, String>, val how: String)

    fun examples(r: JSONObject): Map<String, String> {
        val arr = r.optJSONArray("slots") ?: return emptyMap()
        return (0 until arr.length()).associate { arr.getJSONObject(it).let { s -> s.getString("name") to s.optString("example") } }
    }

    fun match(utterance: String, recipes: List<JSONObject>): Match? {
        val u = Text.norm(utterance)
        for (r in recipes.asReversed()) {
            if (Text.norm(r.optString("command")) == u) return Match(r, examples(r), "exact")
        }
        for (r in recipes.asReversed()) {
            val names = ArrayList<String>()
            val pattern = r.optString("template").split(' ').filter { it.isNotEmpty() }.joinToString("\\s+") { tok ->
                val m = Regex("^\\{([a-z0-9_]+)\\}$").find(tok)
                if (m != null) { names.add(m.groupValues[1]); "(.+?)" } else Regex.escape(tok)
            }
            val hit = Regex("^$pattern$").find(u) ?: continue
            // "order a margherita pizza" → item "margherita pizza", not "a margherita pizza".
            val values = names.mapIndexed { i, n -> n to hit.groupValues[i + 1].replace(Regex("^(a|an|some)\\s+"), "") }.toMap()
            // A value that swallowed an extra request ("dominos and deliver it to work", "2 of them
            // please") means the command says more than the template: let the language model read it.
            if (values.values.any { v -> v.split(' ').size > 4 || Regex("\\b(and|with|to|for|then|deliver|please)\\b").containsMatchIn(v) }) continue
            // A misheard command ("order of farmhouse piece of from …") leaves filler around the value:
            // let the language model read what was meant.
            if (values.values.any { v -> Regex("^(of|the|to)\\s|\\s(of|the|a)$").containsMatchIn(v.trim()) }) continue
            // "two margherita pizzas" carries a quantity the template has no place for.
            if (values.filterKeys { it != "quantity" }.values.any { v -> QTY_START.containsMatchIn(v) }) continue
            return Match(r, values, "template")
        }
        return null
    }
}
