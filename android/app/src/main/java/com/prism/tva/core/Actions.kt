package com.prism.tva.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Everything the assistant can do to another app: accessibility actions first, gestures as fallback. */
object Actions {

    fun click(n: UiNode): Boolean =
        runCatching { n.info?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }.getOrDefault(false)

    fun longClick(n: UiNode): Boolean =
        runCatching { n.info?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true }.getOrDefault(false)

    fun setText(n: UiNode, text: String): Boolean {
        val info = n.info ?: return false
        return runCatching {
            info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    /** Presses the keyboard's search/enter key on a text field (Android 11+). */
    fun imeEnter(n: UiNode): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return runCatching {
            n.info?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        }.getOrDefault(false)
    }

    fun scroll(n: UiNode, forward: Boolean): Boolean = runCatching {
        n.info?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) == true
    }.getOrDefault(false)

    suspend fun gesture(svc: AccessibilityService, path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 5000)))
                .build()
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }
            val ok = runCatching { svc.dispatchGesture(g, cb, null) }.getOrDefault(false)
            if (!ok && cont.isActive) cont.resume(false)
        }

    fun tapPath(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    suspend fun tap(svc: AccessibilityService, x: Float, y: Float, long: Boolean = false) =
        gesture(svc, tapPath(x, y), if (long) 700 else 60)

    suspend fun swipe(svc: AccessibilityService, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 350) =
        gesture(svc, Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    /** Opens an app the normal way (its launcher entry), starting from its home screen. */
    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    fun homePackage(ctx: Context): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
    }

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(ai).toString()
    }.getOrDefault(pkg)
}
