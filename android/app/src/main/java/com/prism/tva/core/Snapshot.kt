package com.prism.tva.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/** One window on screen at capture time (app, keyboard, system bar, dialog...). */
class WindowRec(
    val id: Int,
    val type: Int,
    val layer: Int,
    val pkg: String,
    val title: String?,
    val bounds: Rect,
    val active: Boolean,
) {
    val isApp get() = type == AccessibilityWindowInfo.TYPE_APPLICATION
    val isIme get() = type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    val isSystem get() = type == AccessibilityWindowInfo.TYPE_SYSTEM
}

/** One element on screen. Plain fields so a snapshot can be scored, logged and sent to an LLM. */
class UiNode(
    val idx: Int,
    val parent: Int,
    val depth: Int,
    val window: Int,
    val viewId: String?,
    val cls: String,
    val text: String?,
    val desc: String?,
    val hint: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val password: Boolean,
    val visible: Boolean,
    val info: AccessibilityNodeInfo?,
) {
    val children = ArrayList<Int>(4)
    val shortId: String get() = viewId?.substringAfterLast('/') ?: ""
    val shortCls: String get() = cls.substringAfterLast('.')

    /** Visible text, else content description. Password text is never exposed. */
    val label: String by lazy {
        if (password) "" else Text.clean(text).ifEmpty { Text.clean(desc) }
    }
    val normLabel: String by lazy { Text.norm(label) }

    fun toJson(): JSONObject = JSONObject()
        .put("i", idx).put("p", parent).put("w", window)
        .put("id", shortId).put("cls", shortCls)
        .put("label", label).put("hint", hint ?: "")
        .put("b", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
        .put("flags", buildString {
            if (clickable) append('C'); if (longClickable) append('L'); if (editable) append('E')
            if (scrollable) append('S'); if (checked) append('K'); if (selected) append('X')
            if (focused) append('F'); if (password) append('P'); if (!visible) append('h')
        })
}

class Hit(val leaf: UiNode, val target: UiNode, val window: WindowRec)

class Snapshot(
    val nodes: List<UiNode>,
    val windows: List<WindowRec>,
    val screenW: Int,
    val screenH: Int,
    val activity: String?,
    val takenAt: Long,
) {
    /** Package of the foreground app. */
    val appPkg: String =
        windows.firstOrNull { it.isApp && it.active }?.pkg ?: windows.firstOrNull { it.isApp }?.pkg ?: ""

    fun parentOf(n: UiNode): UiNode? = if (n.parent >= 0) nodes[n.parent] else null

    fun subtree(n: UiNode): Sequence<UiNode> = sequence {
        val stack = ArrayDeque<Int>()
        stack.addLast(n.idx)
        while (stack.isNotEmpty()) {
            val x = nodes[stack.removeLast()]
            yield(x)
            for (c in x.children.asReversed()) stack.addLast(c)
        }
    }

    /** Distinct visible labels inside [n], in reading order. */
    fun labelsIn(n: UiNode, limit: Int = 8): List<String> {
        val out = LinkedHashMap<String, String>()
        for (x in subtree(n)) {
            if (!x.visible || x.label.isEmpty()) continue
            out.putIfAbsent(x.normLabel, x.label.take(90))
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    /** Nearest self-or-ancestor that can take a click or text. */
    fun actionable(n: UiNode): UiNode {
        var cur: UiNode? = n
        var hops = 0
        while (cur != null && hops < 12) {
            if (cur.clickable || cur.editable) return cur
            cur = parentOf(cur)
            hops++
        }
        return n
    }

    /**
     * The row/card around [n], e.g. the dish card around an ADD button. Its labels are what make "the
     * ADD next to Margherita" distinguishable from every other ADD.
     *
     * When the screen has other elements just like [n] (other ADD buttons), the card is the LARGEST
     * ancestor that holds no other one: Zomato wraps ADD with a "customisable" note in a small box,
     * and the dish name sits two levels up. Without look-alikes it's the nearest ancestor with any
     * other label. Either way it stays under half the screen tall.
     */
    fun rowContainer(n: UiNode): UiNode? {
        val own = labelsIn(n, 12).map { Text.norm(it) }.toSet()
        val others = twins(n).filter { it.idx != n.idx }
        var cur = parentOf(n)
        var hops = 0
        var found: UiNode? = null
        while (cur != null && hops < 14) {
            if (cur.bounds.height() > screenH * 0.5) break
            val c = cur
            if (others.any { isInside(it, c) }) break
            if (labelsIn(c, 24).any { val k = Text.norm(it); k.isNotEmpty() && k !in own }) {
                if (others.isEmpty()) return c
                found = c
            }
            cur = parentOf(c)
            hops++
        }
        return found
    }

    /** Elements that look like [n] (same id, class and label, ignoring numbers), top to bottom. */
    fun twins(n: UiNode): List<UiNode> {
        val key = Text.stable(n.label.ifEmpty { labelsIn(n, 1).firstOrNull() ?: "" })
        return appNodes(windows[n.window].pkg)
            .filter { it.shortId == n.shortId && it.shortCls == n.shortCls && Text.stable(it.label.ifEmpty { labelsIn(it, 1).firstOrNull() ?: "" }) == key }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun isInside(n: UiNode, ancestor: UiNode): Boolean {
        var cur = parentOf(n)
        var hops = 0
        while (cur != null && hops < 40) {
            if (cur.idx == ancestor.idx) return true
            cur = parentOf(cur)
            hops++
        }
        return false
    }

    fun rowLabels(n: UiNode, limit: Int = 14): List<String> {
        val own = labelsIn(n, 12).map { Text.norm(it) }.toSet()
        val row = rowContainer(n) ?: return emptyList()
        return labelsIn(row, 30).filter { Text.norm(it) !in own }.take(limit)
    }

    fun imeTop(): Int = windows.firstOrNull { it.isIme }?.bounds?.top ?: -1

    /** Topmost element under a screen point, and the element that would actually take the tap. */
    fun hitTest(x: Int, y: Int): Hit? {
        // Windows are topmost first. Some system windows (e.g. ColorOS's edge panel) cover the whole
        // screen but hold nothing under the finger, so keep going until a window takes the touch.
        for (wi in windows.indices) {
            if (!windows[wi].bounds.contains(x, y)) continue
            val roots = nodes.filter { it.window == wi && it.parent < 0 }
            val outer = roots.firstNotNullOfOrNull { dispatch(it, x, y) } ?: continue
            // Web views and some Compose trees report child boxes that lie outside their parents',
            // so a tree walk can stop at the WebView itself. Look inside for the most specific
            // clickable element under the finger (the "Add to cart" button, not the whole page).
            val target = subtree(outer)
                .filter { it !== outer && it.visible && (it.clickable || it.editable) && it.bounds.contains(x, y) && area(it) > 0 }
                .minByOrNull { area(it) } ?: outer
            return Hit(labelUnder(target, x, y) ?: target, target, windows[wi])
        }
        return null
    }

    /**
     * Mirrors Android's touch dispatch: children are tried topmost-first (last drawn = on top), and a
     * touch lands on the deepest clickable element under the finger. Views that don't take clicks
     * (backgrounds, transparent wrappers) let it fall through to what is underneath.
     */
    private fun dispatch(n: UiNode, x: Int, y: Int): UiNode? {
        if (!n.visible || !n.bounds.contains(x, y)) return null
        for (c in n.children.asReversed()) dispatch(nodes[c], x, y)?.let { return it }
        return if (n.clickable || n.editable || n.longClickable) n else null
    }

    /** The label the user actually tapped on inside [target] (topmost labelled element under the finger). */
    private fun labelUnder(target: UiNode, x: Int, y: Int): UiNode? =
        subtree(target).filter { it.visible && it.label.isNotEmpty() && it.bounds.contains(x, y) }
            .minByOrNull { area(it) }
            ?: subtree(target).firstOrNull { it.visible && it.label.isNotEmpty() }

    private fun area(n: UiNode) = n.bounds.width().toLong() * n.bounds.height()

    fun appNodes(pkg: String = appPkg): List<UiNode> =
        nodes.filter { it.visible && windows[it.window].pkg == pkg && !windows[it.window].isIme }

    fun visibleLabels(pkg: String = appPkg, limit: Int = 60): List<String> {
        val out = LinkedHashMap<String, String>()
        for (n in nodes) {
            if (!n.visible || n.label.isEmpty() || windows[n.window].pkg != pkg) continue
            out.putIfAbsent(n.normLabel, n.label.take(80))
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    /** What we store about a tapped element so it can be found again on a changed screen. */
    fun describe(hit: Hit): JSONObject {
        val t = hit.target
        val own = labelsIn(t, 8)
        val leafLabel = hit.leaf.label.ifEmpty { own.firstOrNull() ?: "" }
        val twins = twins(t)
        return JSONObject()
            .put("id", t.shortId).put("cls", t.shortCls)
            .put("label", t.label).put("leafLabel", leafLabel)
            .put("ownLabels", JSONArray(own))
            .put("rowLabels", JSONArray(rowLabels(t)))
            .put("hint", Text.clean(t.hint))
            .put("clickable", t.clickable).put("editable", t.editable)
            .put("pkg", windows[t.window].pkg)
            .put("rx", t.bounds.exactCenterX() / screenW)
            .put("ry", t.bounds.exactCenterY() / screenH)
            .put("similarIndex", twins.indexOfFirst { it.idx == t.idx })
            .put("similarCount", twins.size)
    }

    /** Field description for a text box (from an event source or a snapshot node). */
    fun describeField(n: UiNode): JSONObject = JSONObject()
        .put("id", n.shortId).put("cls", n.shortCls)
        .put("label", if (n.password) "" else n.label).put("hint", Text.clean(n.hint))
        .put("editable", true).put("pkg", windows[n.window].pkg)
        .put("rx", n.bounds.exactCenterX() / screenW).put("ry", n.bounds.exactCenterY() / screenH)

    fun toJson(pkg: String? = null): JSONObject {
        val arr = JSONArray()
        for (n in nodes) if (pkg == null || windows[n.window].pkg == pkg) arr.put(n.toJson())
        val ws = JSONArray()
        windows.forEach { w ->
            ws.put(JSONObject().put("type", w.type).put("layer", w.layer).put("pkg", w.pkg)
                .put("title", w.title ?: "").put("active", w.active)
                .put("b", "${w.bounds.left},${w.bounds.top},${w.bounds.right},${w.bounds.bottom}"))
        }
        return JSONObject().put("w", screenW).put("h", screenH).put("activity", activity ?: "")
            .put("appPkg", appPkg).put("windows", ws).put("nodes", arr)
    }

    companion object {
        fun realSize(ctx: Context): Pair<Int, Int> {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            return dm.widthPixels to dm.heightPixels
        }

        /** Reads every window on screen except our own overlays. Windows are ordered topmost first. */
        fun capture(svc: AccessibilityService, ownPkg: String, activity: String?, maxNodes: Int = 3000): Snapshot {
            val (sw, sh) = realSize(svc)
            val nodes = ArrayList<UiNode>(512)
            val wrecs = ArrayList<WindowRec>()
            val wins = try { svc.windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
            for (w in wins.sortedByDescending { it.layer }) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == ownPkg) continue
                val r = Rect()
                w.getBoundsInScreen(r)
                val wi = wrecs.size
                wrecs.add(WindowRec(w.id, w.type, w.layer, pkg, w.title?.toString(), r, w.isActive))
                val stack = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Int>>()
                stack.addLast(Triple(root, -1, 0))
                while (stack.isNotEmpty() && nodes.size < maxNodes) {
                    val (n, p, d) = stack.removeLast()
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    val idx = nodes.size
                    nodes.add(
                        UiNode(
                            idx, p, d, wi, n.viewIdResourceName, n.className?.toString() ?: "",
                            n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString(), b,
                            n.isClickable, n.isLongClickable, n.isEditable, n.isScrollable, n.isChecked,
                            n.isSelected, n.isFocused, n.isPassword, n.isVisibleToUser, n,
                        )
                    )
                    if (p >= 0) nodes[p].children.add(idx)
                    for (i in n.childCount - 1 downTo 0) {
                        val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                        stack.addLast(Triple(c, idx, d + 1))
                    }
                }
            }
            return Snapshot(nodes, wrecs, sw, sh, activity, System.currentTimeMillis())
        }

        /**
         * Rebuilds a snapshot saved by [toJson] (a teach recording keeps one per tap), so a recording
         * can be re-described and re-learned with improved rules without teaching it again.
         */
        fun fromJson(j: JSONObject): Snapshot {
            fun rect(s: String) = s.split(',').map { it.trim().toInt() }.let { Rect(it[0], it[1], it[2], it[3]) }
            val ws = j.optJSONArray("windows") ?: JSONArray()
            val wrecs = (0 until ws.length()).map { k ->
                val w = ws.getJSONObject(k)
                WindowRec(k, w.optInt("type"), w.optInt("layer"), w.optString("pkg"), w.optString("title"),
                    rect(w.getString("b")), w.optBoolean("active"))
            }
            val arr = j.getJSONArray("nodes")
            val index = HashMap<Int, Int>()
            for (k in 0 until arr.length()) index[arr.getJSONObject(k).getInt("i")] = k
            val nodes = ArrayList<UiNode>(arr.length())
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val f = o.optString("flags")
                val p = index[o.optInt("p", -1)] ?: -1
                nodes.add(UiNode(k, p, if (p >= 0) nodes[p].depth + 1 else 0, o.optInt("w"),
                    o.optString("id").ifEmpty { null }, o.optString("cls"), o.optString("label"), null,
                    o.optString("hint").ifEmpty { null }, rect(o.getString("b")),
                    'C' in f, 'L' in f, 'E' in f, 'S' in f, 'K' in f, 'X' in f, 'F' in f, 'P' in f, 'h' !in f, null))
                if (p >= 0) nodes[p].children.add(k)
            }
            return Snapshot(nodes, wrecs, j.optInt("w"), j.optInt("h"), j.optString("activity").ifEmpty { null }, 0)
        }
    }
}
