package io.github.srqingchen.chenlu.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import io.github.srqingchen.chenlu.service.AutomationService

/**
 * 悬浮窗宿主：M0 管理控制球；M2 起承载十字准星与尘露岛。
 * 所有窗口使用 TYPE_APPLICATION_OVERLAY，需用户授予“显示悬浮窗”权限。
 */
object OverlayHost {

    private var ball: ControlBallView? = null

    val isBallShown: Boolean get() = ball != null

    /** @return 是否成功展示（无悬浮窗权限时返回 false）。 */
    fun showBall(context: Context): Boolean {
        if (ball != null) return true
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return false
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (appContext.resources.displayMetrics.density * 48).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 320
        }
        val view = ControlBallView(appContext).apply {
            onToggle = { AutomationService.toggle(appContext) }
        }
        wm.addView(view, params)
        ball = view
        return true
    }

    fun hideBall(context: Context) {
        val view = ball ?: return
        val wm = context.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(view) }
        ball = null
    }
}
