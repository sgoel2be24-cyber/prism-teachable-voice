package com.prism.tva.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small status pill drawn over other apps (over the status bar, where apps rarely have targets).
 * Shows what the assistant is doing, with buttons such as Done / Cancel / Stop.
 */
class Hud(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var label: TextView? = null
    private val hideTask = Runnable { removeNow() }

    /**
     * [compact]: one short line inside the status bar, for teaching. The user is tapping the app
     * then, and a pill below the status bar would sit on the app's own toolbar (GitHub's search
     * icon, a back arrow), so those taps would never reach it.
     */
    fun show(message: String, buttons: List<Pair<String, () -> Unit>> = emptyList(), autoHideMs: Long = 0, compact: Boolean = false) {
        main.post {
            removeNow()
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.resources.displayMetrics).toInt() }
            val size = if (compact) 12f else 13f
            val tv = TextView(svc).apply {
                text = message
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                maxLines = if (compact) 1 else 2
                setPadding(dp(12f), if (compact) dp(2f) else dp(6f), dp(8f), if (compact) dp(2f) else dp(6f))
            }
            val row = LinearLayout(svc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0xEE1F1F24.toInt()) }
                setPadding(dp(4f), 0, dp(4f), 0)
                if (compact) addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                else addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            for ((text, action) in buttons) {
                row.addView(TextView(svc).apply {
                    this.text = text
                    setTextColor(0xFF1F1F24.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                    setPadding(dp(12f), if (compact) dp(3f) else dp(5f), dp(12f), if (compact) dp(3f) else dp(5f))
                    background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(0xFFF5C518.toInt()) }
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(4f)
                    if (compact) { topMargin = dp(2f); bottomMargin = dp(2f) }
                })
            }
            val lp = WindowManager.LayoutParams(
                if (compact) WindowManager.LayoutParams.WRAP_CONTENT else (svc.resources.displayMetrics.widthPixels * 0.96).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (compact) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = if (compact) 0 else dp(2f)
                if (compact && Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            runCatching { wm.addView(row, lp) }
            root = row
            label = tv
            if (autoHideMs > 0) main.postDelayed(hideTask, autoHideMs)
        }
    }

    fun update(message: String) = main.post { label?.text = message }

    /**
     * Lets touches fall through the pill (while the assistant itself taps or swipes, so its own
     * gesture can't land on the pill's Stop button when the target sits under it).
     */
    fun setTouchable(on: Boolean) = main.post {
        val v = root ?: return@post
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@post
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
    }

    fun hide() = main.post { removeNow() }

    private fun removeNow() {
        main.removeCallbacks(hideTask)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        label = null
    }
}
