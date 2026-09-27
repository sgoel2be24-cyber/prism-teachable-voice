package com.prism.tva.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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

    fun show(message: String, buttons: List<Pair<String, () -> Unit>> = emptyList(), autoHideMs: Long = 0) {
        main.post {
            removeNow()
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.resources.displayMetrics).toInt() }
            val tv = TextView(svc).apply {
                text = message
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                setPadding(dp(12f), dp(6f), dp(8f), dp(6f))
            }
            val row = LinearLayout(svc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0xEE1F1F24.toInt()) }
                setPadding(dp(4f), 0, dp(4f), 0)
                addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            for ((text, action) in buttons) {
                row.addView(TextView(svc).apply {
                    this.text = text
                    setTextColor(0xFF1F1F24.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
                    background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(0xFFF5C518.toInt()) }
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4f) })
            }
            val lp = WindowManager.LayoutParams(
                (svc.resources.displayMetrics.widthPixels * 0.96).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(2f) }
            runCatching { wm.addView(row, lp) }
            root = row
            label = tv
            if (autoHideMs > 0) main.postDelayed(hideTask, autoHideMs)
        }
    }

    fun update(message: String) = main.post { label?.text = message }

    fun hide() = main.post { removeNow() }

    private fun removeNow() {
        main.removeCallbacks(hideTask)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        label = null
    }
}
