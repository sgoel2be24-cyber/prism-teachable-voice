package com.prism.tva.run

import com.prism.tva.core.Snapshot
import com.prism.tva.core.Text
import com.prism.tva.core.UiNode
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Fast path: finds the element a recipe step means on the current screen, with no LLM.
 * Every candidate is scored on label (with slot values substituted), view id, the card it sits in
 * (anchor), editability and position; the best one wins if it is clearly ahead.
 */
object Resolver {

    /** View ids that say nothing about what an element is. */
    private val GENERIC_IDS = setOf(
        "", "title", "subtitle", "text", "button", "container", "root", "content", "image", "icon", "view",
        "layout", "ll_root", "text_view_title", "image_view", "tv_title", "right_title", "left_title",
        "bottom_title", "item", "card", "parent", "main",
    )

    class Match(val node: UiNode, val score: Double, val runnerUp: Double, val why: String)

    fun resolve(step: JSONObject, slots: Map<String, String>, snap: Snapshot, pkg: String): Match? {
        val ranked = rank(step, slots, snap, pkg)
        if (ranked.isEmpty()) return null
        var top = ranked[0]
        // A container (tab bar, card) inherits its children's labels; when it scores about the same
        // as one of its own children, the child is what the user meant.
        ranked.drop(1).firstOrNull { it.score >= top.score - 0.6 && isAncestor(snap, top.node, it.node) }?.let { top = it }
        // Runner-up = best candidate that is not the same element seen through a parent or child.
        val second = ranked.firstOrNull {
            it !== top && !isAncestor(snap, it.node, top.node) && !isAncestor(snap, top.node, it.node)
        }?.score ?: -99.0
        val clear = top.score - second >= 0.5 || top.score >= 6.5
        if (top.score >= 3.0 && clear) return Match(top.node, top.score, second, top.why)
        // Several identical elements ("Add to cart" on every result): take the one in the same position
        // among its twins as in the demonstration (top-to-bottom), i.e. "the first result".
        val t = step.optJSONObject("target")
        val twinIndex = t?.optInt("similarIndex", -1) ?: -1
        if (top.score >= 3.0 && twinIndex >= 0) {
            val key = Text.stable(top.node.label.ifEmpty { snap.labelsIn(top.node, 1).firstOrNull() ?: "" })
            val twins = ranked.filter { it.score >= top.score - 0.6 &&
                Text.stable(it.node.label.ifEmpty { snap.labelsIn(it.node, 1).firstOrNull() ?: "" }) == key }
                .sortedWith(compareBy({ it.node.bounds.top }, { it.node.bounds.left }))
            if (twins.size >= 2) {
                val pick = twins[minOf(twinIndex, twins.size - 1)]
                return Match(pick.node, pick.score, second, pick.why + " twin#$twinIndex")
            }
        }
        return null
    }

    private fun isAncestor(snap: Snapshot, a: UiNode, b: UiNode): Boolean {
        var cur = snap.parentOf(b)
        while (cur != null) {
            if (cur.idx == a.idx) return true
            cur = snap.parentOf(cur)
        }
        return false
    }

    /** All candidates, best first (also used by the EXPLAIN dev command). */
    fun rank(step: JSONObject, slots: Map<String, String>, snap: Snapshot, pkg: String): List<Match> {
        val kind = step.optString("kind")
        val t = step.optJSONObject("target") ?: step.optJSONObject("field") ?: return emptyList()
        val wantEditable = kind == "type" || t.optBoolean("editable")

        val textSlot = step.optString("textSlot")
        val slotValue = if (textSlot.isNotEmpty()) slots[textSlot].orEmpty() else ""
        val literal = t.optString("leafLabel").ifEmpty { t.optString("label") }
        val anchorSlotValue = step.optString("anchorSlot").takeIf { it.isNotEmpty() }?.let { slots[it] }.orEmpty()
        val anchorLiteral = step.optString("anchorText")
        val id = t.optString("id")
        val cls = t.optString("cls")
        val hint = t.optString("hint")
        val rx = t.optDouble("rx", 0.5)
        val ry = t.optDouble("ry", 0.5)

        val best = HashMap<Int, Pair<Double, String>>()
        for (n in snap.nodes) {
            val w = snap.windows[n.window]
            if (!n.visible || w.pkg != pkg || w.isIme) continue
            if (n.label.isEmpty() && n.shortId.isEmpty() && !n.editable) continue
            val a = snap.actionable(n)
            if (!a.visible) continue
            if (wantEditable && !a.editable) continue
            val why = StringBuilder()
            var s = 0.0

            // View id. A distinctive id match also forgives a changed label (e.g. a rotating hint).
            var idStrong = false
            if (id.isNotEmpty()) {
                if (a.shortId == id || n.shortId == id) {
                    val g = id in GENERIC_IDS
                    idStrong = !g
                    s += if (g) 1.0 else 3.0
                    why.append(if (g) "gid " else "id ")
                } else if (a.shortId.isNotEmpty() && id !in GENERIC_IDS) s -= 0.5
            }
            val miss = if (idStrong) 0.5 else 2.0

            // Label.
            val labels = (listOf(n.label, a.label) + snap.labelsIn(a, 8)).filter { it.isNotEmpty() }.distinct()
            if (slotValue.isNotEmpty()) {
                when {
                    labels.any { Text.norm(it) == Text.norm(slotValue) } -> { s += 5.0; why.append("slot= ") }
                    labels.any { Text.norm(it).startsWith(Text.norm(slotValue)) } -> { s += 4.5; why.append("slot^ ") }
                    labels.any { Text.fuzzyContains(it, slotValue) } -> { s += 4.0; why.append("slot~ ") }
                    else -> s -= miss
                }
            } else if (literal.isNotEmpty() && !wantEditable) {
                val nl = Text.norm(literal)
                val sl = Text.stable(literal)
                when {
                    labels.any { Text.norm(it) == nl } -> { s += 4.5; why.append("label= ") }
                    sl.length >= 3 && labels.any { Text.stable(it) == sl } -> { s += 3.5; why.append("stable= ") }
                    sl.length >= 3 && labels.any { Text.overlap(it, literal) >= 0.75 } -> { s += 3.0; why.append("loose ") }
                    nl.length >= 3 && labels.any { Text.norm(it).contains(nl) || (Text.norm(it).length >= 3 && nl.contains(Text.norm(it))) } -> { s += 2.0; why.append("label~ ") }
                    labels.any { Text.sim(it, literal) >= 0.8 } -> { s += 2.0; why.append("sim ") }
                    else -> s -= miss
                }
            }
            if (wantEditable && hint.isNotEmpty() && Text.sim(a.hint, hint) >= 0.8) { s += 1.5; why.append("hint ") }
            if (cls.isNotEmpty() && a.shortCls == cls) s += 0.3
            if (a.editable != wantEditable) s -= 3.0
            if (wantEditable && a.focused) { s += 2.0; why.append("focused ") }

            // Anchor: the card the element sits in ("ADD" next to "Margherita").
            if (anchorSlotValue.isNotEmpty() || anchorLiteral.isNotEmpty()) {
                val row = snap.rowLabels(a) + labels
                if (anchorSlotValue.isNotEmpty()) {
                    val soft = step.optBoolean("anchorSoft")
                    if (row.any { Text.fuzzyContains(it, anchorSlotValue) }) { s += if (soft) 2.0 else 4.0; why.append("anchor ") }
                    else s -= if (soft) 0.5 else 4.0
                } else {
                    if (row.any { Text.fuzzyContains(it, anchorLiteral) }) { s += 2.0; why.append("anchorLit ") } else s -= 0.5
                }
            }

            // Position on screen (weak: layouts shift).
            val d = hypot(a.bounds.exactCenterX() / snap.screenW - rx, a.bounds.exactCenterY() / snap.screenH - ry)
            s += (1.0 - minOf(1.0, d * 2)) * 1.0

            val prev = best[a.idx]
            if (prev == null || s > prev.first) best[a.idx] = s to why.toString().trim()
        }
        return best.entries.sortedByDescending { it.value.first }
            .map { Match(snap.nodes[it.key], it.value.first, 0.0, it.value.second) }
    }

    /** The biggest scrollable area of the app, used to look for off-screen targets. */
    fun mainScrollable(snap: Snapshot, pkg: String): UiNode? =
        snap.nodes.filter { it.visible && it.scrollable && snap.windows[it.window].pkg == pkg }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
}
