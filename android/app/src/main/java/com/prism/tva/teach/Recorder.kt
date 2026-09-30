package com.prism.tva.teach

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.prism.tva.TvaAccessibilityService
import com.prism.tva.core.Actions
import com.prism.tva.core.Dbg
import com.prism.tva.core.Hit
import com.prism.tva.core.Snapshot
import com.prism.tva.core.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Teach mode. A transparent overlay catches every touch outside the keyboard, snapshots the screen
 * just before the touch lands, works out which element was touched, then passes the touch on to the
 * app (as an accessibility click when the element is clickable, otherwise as an injected gesture).
 * Typing is read from text-change events; a keyboard search/enter is recorded as a submit.
 *
 * Why an overlay: Compose screens and web views (Zomato results, Amazon) don't report touch taps to
 * accessibility services, so event-only recording misses about 40% of taps (docs/spike-results.md).
 */
class Recorder(private val svc: TvaAccessibilityService) {

    companion object {
        private const val TAP_SLOP = 28f
        private const val LONG_PRESS_MS = 550L
        private const val EDGE = 56
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val bg = Executors.newSingleThreadExecutor()

    @Volatile var active = false
        private set
    var onFinished: ((JSONObject?) -> Unit)? = null

    // --- state below is only touched on the bg thread ---
    private var command = ""
    private var recId = ""
    private val steps = ArrayList<JSONObject>()
    private var snapDir: File? = null
    private var snapCount = 0
    private var pendingField: JSONObject? = null
    private var pendingText: String? = null
    private var pendingSecret = false
    private var pendingAt = 0L
    private var lastActionAt = 0L

    // --- main-thread state ---
    private var catcher: CatcherView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var screenW = 0
    private var screenH = 0
    @Volatile private var imeTop = -1

    fun start(cmd: String) {
        if (active) stop(save = false)
        val (w, h) = Snapshot.realSize(svc)
        screenW = w; screenH = h
        bg.submit(Callable {
            command = cmd
            recId = "rec_" + System.currentTimeMillis()
            steps.clear(); snapCount = 0
            pendingField = null; pendingText = null; lastActionAt = 0
            snapDir = svc.store.snapDir(recId)
            Unit
        }).get(2, TimeUnit.SECONDS)
        imeTop = -1
        active = true
        Dbg.log("TEACH_START \"$cmd\"")
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        main.postDelayed({
            if (!active) return@postDelayed
            addCatcher()
            // Kept inside the status bar so it never covers a button the user needs to tap.
            svc.hud.show("● Teaching",
                listOf("Done" to { finish() }, "Cancel" to { stop(save = false); svc.speaker.say("Cancelled.") }), compact = true)
        }, 700)
    }

    /** Done button: save the recording and hand it to the service to build a recipe. */
    fun finish() {
        val rec = stop(save = true)
        onFinished?.invoke(rec)
    }

    fun stop(save: Boolean): JSONObject? {
        if (!active) return null
        active = false
        main.post { removeCatcher(); svc.hud.hide() }
        return bg.submit(Callable {
            flushText(submit = false)
            if (!save) {
                Dbg.log("TEACH_CANCEL $recId")
                null
            } else {
                val rec = JSONObject()
                    .put("id", recId).put("command", command)
                    .put("createdAt", System.currentTimeMillis())
                    .put("screenW", screenW).put("screenH", screenH)
                    .put("steps", JSONArray(steps.toList()))
                svc.store.saveRecording(recId, rec)
                Dbg.log("TEACH_STOP $recId steps=${steps.size}")
                rec
            }
        }).get(5, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ accessibility events

    fun onEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> Dbg.log("REC app-click ${e.packageName} ${e.source?.viewIdResourceName?.substringAfterLast('/') ?: ""} ${e.text}")
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Dbg.log("REC window ${e.className}")
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> main.post { refreshIme() }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Only typing counts: apps also rewrite text boxes themselves after navigation.
                if (imeTop < 0) return
                val src = e.source ?: return
                if (!src.isEditable) return
                val text = e.text?.joinToString("")?.toString() ?: src.text?.toString() ?: ""
                val secret = e.isPassword || src.isPassword
                val field = fieldJson(src)
                bg.execute {
                    pendingField = field
                    pendingText = text
                    pendingSecret = secret
                    pendingAt = System.currentTimeMillis()
                }
            }
        }
    }

    private fun fieldJson(n: AccessibilityNodeInfo): JSONObject {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return JSONObject()
            .put("id", n.viewIdResourceName?.substringAfterLast('/') ?: "")
            .put("cls", n.className?.toString()?.substringAfterLast('.') ?: "")
            .put("hint", Text.clean(n.hintText?.toString()))
            .put("editable", true)
            .put("pkg", n.packageName?.toString() ?: "")
            .put("rx", r.exactCenterX() / screenW).put("ry", r.exactCenterY() / screenH)
    }

    /** Keeps the overlay off the keyboard so typing goes straight to the keyboard. */
    private fun refreshIme() {
        if (!active) return
        val ime = runCatching { svc.windows }.getOrDefault(emptyList())
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val top = ime?.let { Rect().also { r -> it.getBoundsInScreen(r) }.top } ?: -1
        if (top == imeTop) return
        val wasShown = imeTop > 0
        imeTop = top
        catcher?.let { v ->
            lp?.let { p ->
                p.height = if (top > 0) top else screenH
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        if (wasShown && top < 0) bg.execute {
            // Keyboard closed with typed text and no tap since: the user pressed search/enter.
            if (pendingText != null && pendingAt > lastActionAt) flushText(submit = true)
        }
    }

    // ------------------------------------------------------------------ touches

    private fun onGesture(x0: Float, y0: Float, x1: Float, y1: Float, dur: Long, maxD: Float,
                          points: List<FloatArray>, snap: Future<Snapshot>?) {
        val dx = x1 - x0
        val dy = y1 - y0
        when {
            maxD < TAP_SLOP -> onTap(x0, y0, dur >= LONG_PRESS_MS, snap)
            (x0 < EDGE || x0 > screenW - EDGE) && abs(dx) > abs(dy) && abs(dx) > 60 -> {
                bg.execute { flushText(false); addStep(JSONObject().put("kind", "back")) }
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            y0 > screenH - 110 && dy < -150 -> {
                bg.execute { flushText(false); addStep(JSONObject().put("kind", "home")) }
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            else -> onSwipe(x0, y0, dx, dy, dur, points, snap)
        }
    }

    /** The system took over the touch (e.g. its own back gesture). */
    private fun onCancelled(x0: Float) {
        if (x0 < EDGE || x0 > screenW - EDGE) bg.execute { flushText(false); addStep(JSONObject().put("kind", "back")) }
    }

    private fun onTap(x: Float, y: Float, long: Boolean, fut: Future<Snapshot>?) {
        bg.execute {
            val snap = runCatching { fut?.get(2, TimeUnit.SECONDS) }.getOrNull() ?: svc.snapshot()
            val hit = snap.hitTest(x.toInt(), y.toInt())
            flushText(false)
            lastActionAt = System.currentTimeMillis()
            val step = JSONObject()
                .put("kind", if (long) "longpress" else "tap")
                .put("x", x / screenW).put("y", y / screenH)
                .put("pkg", hit?.window?.pkg ?: snap.appPkg)
                .put("windowType", hit?.window?.type ?: -1)
                .put("activity", snap.activity ?: "")
                .put("screen", JSONArray(snap.visibleLabels(snap.appPkg, 40)))
                .put("snap", saveSnap(snap))
            if (hit != null) step.put("target", snap.describe(hit))
            addStep(step)
            Dbg.log("REC tap ${step.optJSONObject("target")?.optString("leafLabel")} [${hit?.target?.shortId}] pkg=${step.optString("pkg")}")
            main.post { inject(hit, x, y, long) }
        }
    }

    private fun onSwipe(x0: Float, y0: Float, dx: Float, dy: Float, dur: Long, points: List<FloatArray>, fut: Future<Snapshot>?) {
        val path = Path().apply {
            moveTo(points.first()[0], points.first()[1])
            for (p in points.drop(1)) lineTo(p[0], p[1])
        }
        passThrough(path, dur.coerceIn(120, 900))
        bg.execute {
            flushText(false)
            lastActionAt = System.currentTimeMillis()
            val snap = runCatching { fut?.get(2, TimeUnit.SECONDS) }.getOrNull()
            addStep(JSONObject().put("kind", "scroll")
                .put("dir", if (abs(dy) >= abs(dx)) (if (dy < 0) "down" else "up") else (if (dx < 0) "left" else "right"))
                .put("pkg", snap?.appPkg ?: ""))
        }
    }

    /**
     * Delivers the user's touch to the app unchanged, as a real touch at the same point. (An
     * accessibility click on the element we *think* was hit can differ from what the finger does.)
     */
    @Suppress("UNUSED_PARAMETER")
    private fun inject(hit: Hit?, x: Float, y: Float, long: Boolean) {
        passThrough(Actions.tapPath(x, y), if (long) 700 else 60)
    }

    /** Makes the overlay untouchable, replays the gesture underneath it, then restores the overlay. */
    private fun passThrough(path: Path, durMs: Long) {
        setTouchable(false)
        val t0 = System.currentTimeMillis()
        main.postDelayed({
            svc.scope.launch(Dispatchers.Main) {
                var ok = false
                try { ok = Actions.gesture(svc, path, durMs) } finally {
                    delay(60)
                    setTouchable(true)
                    Dbg.log("REC passthrough ok=$ok ${System.currentTimeMillis() - t0}ms")
                }
            }
        }, 80)
    }

    private fun setTouchable(on: Boolean) {
        val v = catcher ?: return
        val p = lp ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    // ------------------------------------------------------------------ bg-thread helpers

    private fun addStep(s: JSONObject) {
        s.put("t", System.currentTimeMillis())
        steps.add(s)
    }

    private fun flushText(submit: Boolean) {
        val raw = pendingText ?: return
        // An Enter key can land in the box as a newline before the search fires.
        val text = raw.trimEnd('\n', '\r')
        val submitted = submit || text.length != raw.length
        addStep(JSONObject().put("kind", "type")
            .put("field", pendingField ?: JSONObject())
            .put("text", if (pendingSecret) "" else text)
            .put("secret", pendingSecret)
            .put("submit", submitted)
            .put("pkg", pendingField?.optString("pkg") ?: ""))
        Dbg.log("REC type \"${if (pendingSecret) "***" else text}\" submit=$submit")
        pendingText = null
        pendingField = null
        lastActionAt = System.currentTimeMillis()
    }

    private fun saveSnap(snap: Snapshot): String {
        val dir = snapDir ?: return ""
        val name = "s%02d.json".format(++snapCount)
        runCatching { File(dir, name).writeText(snap.toJson(snap.appPkg).toString()) }
        return name
    }

    // ------------------------------------------------------------------ overlay window

    private fun addCatcher() {
        if (catcher != null) return
        val v = CatcherView(svc)
        val p = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { wm.addView(v, p) }.onFailure { Dbg.log("overlay add failed: $it") }
        catcher = v
        lp = p
        refreshIme()
    }

    private fun removeCatcher() {
        catcher?.let { runCatching { wm.removeView(it) } }
        catcher = null
        lp = null
    }

    private inner class CatcherView(ctx: Context) : View(ctx) {
        private val border = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 10f; color = 0xCCE23744.toInt()
        }
        private var downX = 0f
        private var downY = 0f
        private var downT = 0L
        private var maxD = 0f
        private val points = ArrayList<FloatArray>()
        private var snap: Future<Snapshot>? = null

        override fun onDraw(c: Canvas) {
            c.drawRect(5f, 5f, width - 5f, height - 5f, border)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downT = e.eventTime; maxD = 0f
                    points.clear(); points.add(floatArrayOf(e.rawX, e.rawY, 0f))
                    snap = bg.submit(Callable { svc.snapshot() })
                }
                MotionEvent.ACTION_MOVE -> {
                    maxD = maxOf(maxD, hypot(e.rawX - downX, e.rawY - downY))
                    val t = (e.eventTime - downT).toFloat()
                    if (t - points.last()[2] >= 16f) points.add(floatArrayOf(e.rawX, e.rawY, t))
                }
                MotionEvent.ACTION_UP -> {
                    points.add(floatArrayOf(e.rawX, e.rawY, (e.eventTime - downT).toFloat()))
                    onGesture(downX, downY, e.rawX, e.rawY, e.eventTime - downT, maxD, ArrayList(points), snap)
                }
                MotionEvent.ACTION_CANCEL -> onCancelled(downX)
            }
            return true
        }
    }
}
