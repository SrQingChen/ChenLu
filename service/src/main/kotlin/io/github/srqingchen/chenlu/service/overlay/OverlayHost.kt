package io.github.srqingchen.chenlu.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import io.github.srqingchen.chenlu.core.model.Point
import io.github.srqingchen.chenlu.service.AutomationController
import io.github.srqingchen.chenlu.service.AutomationService

/**
 * 悬浮窗宿主：控制球（可触摸，启停/定位）+ 十字准星（触摸穿透，标记点击目标）。
 *
 * 交互流：拖动控制球时准星跟手 → 松手后球吸附屏幕边缘、准星停留在松手处
 * （准星中心即点击目标点）→ 单击球启停。所有窗口 TYPE_APPLICATION_OVERLAY，
 * 需用户授予“显示悬浮窗”权限。
 */
object OverlayHost {

    private var ball: ControlBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var crosshair: CrosshairView? = null
    private var crosshairParams: WindowManager.LayoutParams? = null

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
            onCenterChanged = { cx, cy -> relocateTarget(appContext, cx, cy) }
            onReleased = { dragged -> if (dragged) snapBallToEdge(appContext) }
        }
        wm.addView(view, params)
        ball = view
        ballParams = params

        // 准星初始位置：已有目标点则用之，否则以球心初始化
        val target = AutomationController.state.value.config.target
        val initialCenter = if (target != Point.ZERO) {
            target
        } else {
            Point(params.x + size / 2f, params.y + size / 2f)
        }
        showCrosshair(appContext, initialCenter)
        return true
    }

    fun hideBall(context: Context) {
        val wm = context.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        ball?.let { runCatching { wm.removeView(it) } }
        crosshair?.let { runCatching { wm.removeView(it) } }
        ball = null
        ballParams = null
        crosshair = null
        crosshairParams = null
    }

    private fun showCrosshair(appContext: Context, center: Point) {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (appContext.resources.displayMetrics.density * 44).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 触摸穿透：注入的点击事件不会命中准星本身
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (center.x - size / 2f).toInt()
            y = (center.y - size / 2f).toInt()
        }
        val view = CrosshairView(appContext)
        wm.addView(view, params)
        crosshair = view
        crosshairParams = params
    }

    /** 准星移动到指定中心点，并同步为控制器目标。 */
    private fun relocateTarget(appContext: Context, centerX: Float, centerY: Float) {
        val params = crosshairParams
        val view = crosshair
        if (params != null && view != null) {
            params.x = (centerX - view.width / 2f).toInt()
            params.y = (centerY - view.height / 2f).toInt()
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            runCatching { wm?.updateViewLayout(view, params) }
        }
        AutomationController.updateConfig { it.copy(target = Point(centerX, centerY)) }
    }

    /** 拖动结束后控制球吸附到最近的左右边缘，不再遮挡目标点。 */
    private fun snapBallToEdge(appContext: Context) {
        val view = ball ?: return
        val params = ballParams ?: return
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val screen = wm.maximumWindowMetrics.bounds
        val centerX = params.x + view.width / 2f
        params.x = if (centerX < screen.width() / 2f) 0 else screen.width() - view.width
        params.y = params.y.coerceIn(0, (screen.height() - view.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(view, params) }
    }
}
