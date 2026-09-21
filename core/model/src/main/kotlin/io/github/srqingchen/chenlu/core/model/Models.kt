package io.github.srqingchen.chenlu.core.model

/** 屏幕坐标（物理像素，屏幕绝对坐标系，含状态栏区域）。 */
data class Point(val x: Float, val y: Float) {
    companion object {
        val ZERO = Point(0f, 0f)
    }
}

/** 多目标点击顺序：顺序循环 / 随机（防检测地基）。 */
enum class TargetOrder { SEQUENTIAL, RANDOM }

/** 带相对时间戳（ms）的轨迹点。 */
data class TimedPoint(val t: Long, val x: Float, val y: Float)

/** 一次录制的单指轨迹（首点=按下，末点=抬起）。 */
data class TouchStroke(val pointerId: Int, val points: List<TimedPoint>) {
    val durationMs: Long get() = if (points.size < 2) 0 else points.last().t - points.first().t
}

/** 图片模板规则：找到模板（多尺度）→ 点击其中心 + 偏移。 */
data class ImageRule(
    val templateFile: String,
    val threshold: Float = 0.80f,
    val dx: Int = 0,
    val dy: Int = 0,
)

/** 颜色条件规则。 */
enum class ColorAction { CLICK_POINT, STOP_TASK }

data class ColorRule(
    val x: Int,
    val y: Int,
    val color: Int,
    val tolerance: Int = 40,
    val action: ColorAction = ColorAction.CLICK_POINT,
)

/** OCR 文字规则：找到包含指定文本的区域 → 点击其中心。 */
data class TextRule(val text: String)

/** 视觉触发配置：按间隔截屏 → 规则求值 → 动作。 */
data class VisionConfig(
    val enabled: Boolean = false,
    val checkIntervalMs: Long = 500L,
    val imageRule: ImageRule? = null,
    val colorRule: ColorRule? = null,
    val textRule: TextRule? = null,
)

/** 连点配置：多点目标 + 节奏 + 顺序 + 完成条件（0 = 不限）+ 滑动模式 + 防检测抖动（0 = 关）+ 录制轨迹 + 视觉触发。 */
data class TapConfig(
    val targets: List<Point> = emptyList(),
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val pressDurationMs: Long = DEFAULT_PRESS_MS,
    val order: TargetOrder = TargetOrder.SEQUENTIAL,
    val totalClicks: Long = 0L,
    val totalDurationMs: Long = 0L,
    val jitterPx: Int = 0,
    val jitterMs: Long = 0L,
    val jitterPressMs: Long = 0L,
    val swipeDx: Float = 0f,
    val swipeDy: Float = 0f,
    val swipeDurationMs: Long = 0L,
    val strokes: List<TouchStroke> = emptyList(),
    val vision: VisionConfig = VisionConfig(),
) {
    /** 单点便捷访问（向后兼容用）。 */
    val target: Point get() = targets.firstOrNull() ?: Point.ZERO

    fun hasFinishCondition(): Boolean = totalClicks > 0 || totalDurationMs > 0

    fun swipeEnabled(): Boolean = swipeDurationMs > 0L

    fun recordingEnabled(): Boolean = strokes.isNotEmpty()

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L
        const val DEFAULT_PRESS_MS = 48L
    }
}

/** 运行状态：UI / 悬浮球 / 通知按钮共享的单一事实源。 */
data class AutomationRunState(
    val running: Boolean = false,
    val config: TapConfig = TapConfig(),
    val executedCount: Long = 0L,
    val activeEngineId: String? = null,
    val taskName: String? = null,
    val lastError: String? = null,
)
