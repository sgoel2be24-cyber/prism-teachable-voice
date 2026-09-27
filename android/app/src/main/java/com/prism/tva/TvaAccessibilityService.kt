package com.prism.tva

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Spike recorder. Answers two questions before we design the real recorder and executor:
 *  1. When a person taps inside Zomato / Amazon, which accessibility events arrive, and do they
 *     identify the tapped element?  -> every event is appended to events.jsonl
 *  2. Can we act on those apps' elements (click / type), and how fast can we read a whole screen?
 *     -> adb-triggered test commands (DEVELOPMENT ONLY; not shipped in the real app):
 *        adb shell am broadcast -a com.prism.tva.DUMP  --es name menu
 *        adb shell am broadcast -a com.prism.tva.CLICK --es text "ADD"     (or --es id button_add)
 *        adb shell am broadcast -a com.prism.tva.TYPE  --es text "dominos"
 *        adb shell am broadcast -a com.prism.tva.MARK  --es note "starting teach"
 */
class TvaAccessibilityService : AccessibilityService() {

    private lateinit var logFile: File
    private var contentChangesSinceLast = 0

    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DUMP -> dumpAllWindows(intent.getStringExtra("name") ?: "dump")
                ACTION_CLICK -> clickFirstMatch(intent.getStringExtra("text"), intent.getStringExtra("id"))
                ACTION_TYPE -> typeIntoFocused(intent.getStringExtra("text") ?: "")
                ACTION_MARK -> log(JSONObject().put("type", "MARK").put("note", intent.getStringExtra("note")))
            }
        }
    }

    override fun onServiceConnected() {
        val dir = getExternalFilesDir(null) ?: filesDir
        logFile = File(dir, "events.jsonl")
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP); addAction(ACTION_CLICK); addAction(ACTION_TYPE); addAction(ACTION_MARK)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commands, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(commands, filter)
        log(JSONObject().put("type", "SERVICE_CONNECTED"))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(commands) }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            contentChangesSinceLast++
            return
        }
        val pkg = event.packageName?.toString() ?: ""
        if (pkg == packageName) return
        val masked = event.isPassword
        val o = JSONObject()
            .put("type", AccessibilityEvent.eventTypeToString(type))
            .put("pkg", pkg)
            .put("cls", event.className?.toString())
            .put("text", if (masked) "***" else event.text?.joinToString(" | "))
            .put("desc", event.contentDescription?.toString())
            .put("contentChanges", contentChangesSinceLast)
        contentChangesSinceLast = 0
        when (type) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                o.put("scrollDX", event.scrollDeltaX).put("scrollDY", event.scrollDeltaY)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                o.put("before", if (masked) "***" else event.beforeText?.toString())
        }
        event.source?.let { src ->
            o.put("src", describe(src))
            val clickable = clickableAncestor(src)
            if (clickable != null && clickable != src) o.put("clickableAncestor", describe(clickable))
        }
        log(o)
    }

    // ---- element description -------------------------------------------------------------

    private fun describe(n: AccessibilityNodeInfo): JSONObject {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return JSONObject()
            .put("id", n.viewIdResourceName)
            .put("cls", n.className?.toString())
            .put("text", if (n.isPassword) "***" else n.text?.toString())
            .put("desc", n.contentDescription?.toString())
            .put("hint", n.hintText?.toString())
            .put("clickable", n.isClickable)
            .put("editable", n.isEditable)
            .put("scrollable", n.isScrollable)
            .put("visible", n.isVisibleToUser)
            .put("bounds", r.flattenToString())
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 10) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    // ---- test commands (development only) ---------------------------------------------------

    private fun dumpAllWindows(name: String) {
        val t0 = System.currentTimeMillis()
        val out = JSONArray()
        var nodeCount = 0
        for (w in windows) {
            val root = w.root ?: continue
            val counter = IntArray(1)
            out.put(
                JSONObject()
                    .put("windowType", w.type).put("layer", w.layer)
                    .put("title", w.title?.toString()).put("active", w.isActive)
                    .put("pkg", root.packageName?.toString())
                    .put("tree", serialize(root, 0, counter))
            )
            nodeCount += counter[0]
        }
        val ms = System.currentTimeMillis() - t0
        val dir = File(logFile.parentFile, "dumps").apply { mkdirs() }
        val f = File(dir, "$name-${System.currentTimeMillis()}.json")
        f.writeText(out.toString())
        log(JSONObject().put("type", "CMD_DUMP").put("file", f.name)
            .put("windows", out.length()).put("nodes", nodeCount).put("ms", ms))
    }

    private fun serialize(n: AccessibilityNodeInfo, depth: Int, counter: IntArray): JSONObject {
        counter[0]++
        val o = describe(n)
        if (depth < 80 && n.childCount > 0) {
            val kids = JSONArray()
            for (i in 0 until n.childCount) n.getChild(i)?.let { kids.put(serialize(it, depth + 1, counter)) }
            o.put("children", kids)
        }
        return o
    }

    private fun clickFirstMatch(text: String?, id: String?) {
        val t0 = System.currentTimeMillis()
        val matches = findNodes { n ->
            n.isVisibleToUser && (
                (text != null && (n.text?.toString()?.contains(text, ignoreCase = true) == true ||
                    n.contentDescription?.toString()?.contains(text, ignoreCase = true) == true)) ||
                    (id != null && n.viewIdResourceName?.endsWith("/$id") == true))
        }
        val result = JSONObject().put("type", "CMD_CLICK").put("text", text).put("id", id)
            .put("matches", matches.size)
        val target = matches.firstOrNull()
        if (target == null) {
            log(result.put("ok", false))
            return
        }
        val clickable = clickableAncestor(target)
        val ok = if (clickable != null) clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else tapCenter(target)
        log(result.put("ok", ok).put("method", if (clickable != null) "ACTION_CLICK" else "gesture")
            .put("target", describe(target)).put("ms", System.currentTimeMillis() - t0))
    }

    private fun typeIntoFocused(text: String) {
        val target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findNodes { it.isEditable && it.isVisibleToUser }.firstOrNull()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
        val o = JSONObject().put("type", "CMD_TYPE").put("text", text).put("ok", ok)
        if (target != null) o.put("target", describe(target))
        log(o)
    }

    private fun findNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val roots = windows.sortedByDescending { it.layer }.mapNotNull { it.root }
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (predicate(n)) found.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return found
    }

    private fun tapCenter(n: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    @Synchronized
    private fun log(o: JSONObject) {
        o.put("t", System.currentTimeMillis())
        val line = o.toString()
        if (::logFile.isInitialized) runCatching { logFile.appendText(line + "\n") }
        Log.d(TAG, line.take(3000))
    }

    companion object {
        private const val TAG = "TVA"
        const val ACTION_DUMP = "com.prism.tva.DUMP"
        const val ACTION_CLICK = "com.prism.tva.CLICK"
        const val ACTION_TYPE = "com.prism.tva.TYPE"
        const val ACTION_MARK = "com.prism.tva.MARK"
    }
}
