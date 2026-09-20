package io.github.srqingchen.chenlu.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.srqingchen.chenlu.core.model.Point
import io.github.srqingchen.chenlu.service.AutomationController
import io.github.srqingchen.chenlu.service.AutomationService

/**
 * 悬浮窗宿主：控制球（可触摸，启停/定位）+ 十字准星（触摸穿透，标记点击目标）。
 *
 * 所有坐标统一使用屏幕绝对坐标系（getLocationOnScreen 换算，含状态栏区域），
 * 与注入事件坐标系一致——修复部分 ROM 上悬浮窗坐标系原点不含状态栏导致的偏移。
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

        // 首帧布局完成后初始化准星：已有目标用目标，否则以球心为准并写回配置（保证两者一致）
        view.post {
            val center = ballScreenCenter()
            val target = AutomationController.state.value.config.target
            if (target != Point.ZERO) {
                showCrosshair(appContext, target)
            } else {
                center?.let {
                    AutomationController.updateConfig { c -> c.copy(target = it) }
                    showCrosshair(appContext, it)
                }
            }
        }
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

    private fun ballScreenCenter(): Point? {
        val view = ball ?: return null
        if (view.width == 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Point(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
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
        // 布局完成后按屏幕坐标系校正位置（消除窗口坐标原点偏差）
        view.post { placeCrosshairCenter(wm, center.x, center.y) }
    }

    /** 拖动中：准星跟随 + 目标点同步（屏幕绝对坐标）。 */
    private fun relocateTarget(appContext: Context, centerX: Float, centerY: Float) {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) placeCrosshairCenter(wm, centerX, centerY)
        AutomationController.updateConfig { it.copy(target = Point(centerX, centerY)) }
    }

    /** 将准星中心摆放至屏幕绝对坐标 (cx, cy)，自动换算窗口坐标系偏移。 */
    private fun placeCrosshairCenter(wm: WindowManager, cx: Float, cy: Float) {
        val view: CrosshairView = crosshair ?: return
        val params: WindowManager.LayoutParams = crosshairParams ?: return
        if (view.width == 0) {
            view.post { placeCrosshairCenter(wm, cx, cy) }
            return
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        // 窗口坐标 → 屏幕坐标的固定偏移（通常为状态栏高度，各 ROM 不同）
        val offsetX = loc[0] - params.x
        val offsetY = loc[1] - params.y
        params.x = (cx - offsetX - view.width / 2f).toInt()
        params.y = (cy - offsetY - view.height / 2f).toInt()
        runCatching { wm.updateViewLayout(view, params) }
    }

    /** 拖动结束后控制球吸附到最近的左右边缘，不再遮挡目标点。 */
    private fun snapBallToEdge(appContext: Context) {
        val view: View = ball ?: return
        val params = ballParams ?: return
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val screen = wm.maximumWindowMetrics.bounds
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val centerX = loc[0] + view.width / 2f
        params.x = if (centerX < screen.width() / 2f) 0 else screen.width() - view.width
        params.y = params.y.coerceIn(0, (screen.height() - view.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(view, params) }
    }
}
