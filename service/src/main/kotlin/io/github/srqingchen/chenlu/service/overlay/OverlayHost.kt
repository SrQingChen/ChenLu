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
 * 悬浮窗宿主：控制球（可触摸，启停/定位）+ 多个十字准星（触摸穿透，标记点击目标）
 * + 全屏目标选点器。
 *
 * 所有坐标统一使用屏幕绝对坐标系（getLocationOnScreen 换算，含状态栏区域），
 * 与注入事件坐标系一致。吸附时控制球与准星重合则上下让位。
 */
object OverlayHost {

    private class CrosshairEntry(
        val view: CrosshairView,
        val params: WindowManager.LayoutParams,
        var centerX: Float,
        var centerY: Float,
    )

    private var ball: ControlBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private val crosshairEntries = mutableListOf<CrosshairEntry>()
    private var picker: TargetPickerView? = null
    private var pickerParams: WindowManager.LayoutParams? = null

    val isBallShown: Boolean get() = ball != null
    val isPicking: Boolean get() = picker != null

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

        // 首帧布局完成后初始化准星：已有目标用目标，否则以球心为准并写回配置
        view.post {
            val targets = AutomationController.state.value.config.targets
            if (targets.isNotEmpty()) {
                syncCrosshairs(appContext, targets)
            } else {
                ballScreenCenter()?.let {
                    AutomationController.updateConfig { c -> c.copy(targets = listOf(it)) }
                    syncCrosshairs(appContext, listOf(it))
                }
            }
        }
        return true
    }

    fun hideBall(context: Context) {
        val wm = context.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        ball?.let { runCatching { wm.removeView(it) } }
        clearCrosshairs(wm)
        removePicker(context)
        ball = null
        ballParams = null
    }

    /** 进入全屏选点模式（需悬浮窗权限）。 */
    fun startTargetPicker(context: Context): Boolean {
        if (picker != null) return true
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return false
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        val view = TargetPickerView(appContext).apply {
            onComplete = { targets ->
                AutomationController.updateConfig { it.copy(targets = targets) }
                removePicker(appContext)
                if (isBallShown) syncCrosshairs(appContext, targets)
            }
            onCancel = { removePicker(appContext) }
        }
        wm.addView(view, params)
        picker = view
        pickerParams = params
        return true
    }

    private fun removePicker(context: Context) {
        val view = picker ?: return
        val wm = context.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(view) }
        picker = null
        pickerParams = null
    }

    private fun ballScreenCenter(): Point? {
        val view = ball ?: return null
        if (view.width == 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Point(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }

    /** 按目标列表重建全部准星（带序号）。 */
    fun syncCrosshairs(appContext: Context, targets: List<Point>) {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clearCrosshairs(wm)
        val density = appContext.resources.displayMetrics.density
        val size = (density * 44).toInt()
        targets.forEachIndexed { index, target ->
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
                x = (target.x - size / 2f).toInt()
                y = (target.y - size / 2f).toInt()
            }
            val view = CrosshairView(appContext).apply {
                label = if (targets.size > 1) "${index + 1}" else null
            }
            wm.addView(view, params)
            val entry = CrosshairEntry(view, params, target.x, target.y)
            crosshairEntries += entry
            view.post { placeEntry(wm, entry, target.x, target.y) }
        }
    }

    private fun clearCrosshairs(wm: WindowManager) {
        crosshairEntries.forEach { runCatching { wm.removeView(it.view) } }
        crosshairEntries.clear()
    }

    /** 拖动控制球：仅单点模式更新目标（多点模式经屏幕选点编辑，拖球不破坏配置）。 */
    private fun relocateTarget(appContext: Context, centerX: Float, centerY: Float) {
        if (AutomationController.state.value.config.targets.size > 1) return
        AutomationController.updateConfig { it.copy(targets = listOf(Point(centerX, centerY))) }
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val entry = crosshairEntries.firstOrNull()
            ?: return syncCrosshairs(appContext, listOf(Point(centerX, centerY)))
        placeEntry(wm, entry, centerX, centerY)
    }

    /** 将准星中心摆放至屏幕绝对坐标 (cx, cy)，自动换算窗口坐标系偏移。 */
    private fun placeEntry(wm: WindowManager, entry: CrosshairEntry, cx: Float, cy: Float) {
        val view: View = entry.view
        if (view.width == 0) {
            view.post { placeEntry(wm, entry, cx, cy) }
            return
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        // 窗口坐标 → 屏幕坐标的固定偏移（通常为状态栏高度，各 ROM 不同）
        val offsetX = loc[0] - entry.params.x
        val offsetY = loc[1] - entry.params.y
        entry.params.x = (cx - offsetX - view.width / 2f).toInt()
        entry.params.y = (cy - offsetY - view.height / 2f).toInt()
        entry.centerX = cx
        entry.centerY = cy
        runCatching { wm.updateViewLayout(view, entry.params) }
    }

    /** 拖动结束后控制球吸附最近边缘；与任一准星重合时上下让位。 */
    private fun snapBallToEdge(appContext: Context) {
        val view: View = ball ?: return
        val params = ballParams ?: return
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val screen = wm.maximumWindowMetrics.bounds
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val ballW = view.width
        val ballH = view.height

        // 水平吸附
        val centerX = loc[0] + ballW / 2f
        val newLeft = if (centerX < screen.width() / 2f) 0 else screen.width() - ballW
        var newTop = params.y.coerceIn(0, (screen.height() - ballH).coerceAtLeast(0))

        // 与准星的重合规避：圆近似（准星半径 + 余量）
        val margin = 24f
        val density = appContext.resources.displayMetrics.density
        val crosshairRadius = density * 44 * 0.6f
        val ballRadius = ballW / 2f
        for (entry in crosshairEntries) {
            val ballCenterY = newTop + ballH / 2f
            val ballCenterX = newLeft + ballW / 2f
            val dx = ballCenterX - entry.centerX
            val dy = ballCenterY - entry.centerY
            val minDist = ballRadius + crosshairRadius + margin
            if (dx * dx + dy * dy < minDist * minDist) {
                // 向远离准星的方向垂直让位；目标在屏幕上半 → 球往下走，反之往上
                newTop = if (entry.centerY < screen.height() / 2f) {
                    (entry.centerY + crosshairRadius + margin + ballH).toInt()
                } else {
                    (entry.centerY - crosshairRadius - margin - ballH).toInt()
                }
                newTop = newTop.coerceIn(0, (screen.height() - ballH).coerceAtLeast(0))
                break
            }
        }

        params.x = newLeft
        params.y = newTop
        runCatching { wm.updateViewLayout(view, params) }
    }
}
