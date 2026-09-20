package io.github.srqingchen.chenlu.core.model

/** 屏幕坐标（物理像素，屏幕绝对坐标系，含状态栏区域）。 */
data class Point(val x: Float, val y: Float) {
    companion object {
        val ZERO = Point(0f, 0f)
    }
}

/** 多目标点击顺序：顺序循环 / 随机（防检测地基）。 */
enum class TargetOrder { SEQUENTIAL, RANDOM }

/** 连点配置：多点目标 + 节奏 + 顺序 + 完成条件（0 = 不限）+ 防检测抖动（0 = 关）。 */
data class TapConfig(
    val targets: List<Point> = emptyList(),
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val pressDurationMs: Long = DEFAULT_PRESS_MS,
    val order: TargetOrder = TargetOrder.SEQUENTIAL,
    val totalClicks: Long = 0L,
    val totalDurationMs: Long = 0L,
    val jitterPx: Int = 0,
    val jitterMs: Long = 0L,
) {
    /** 单点便捷访问（向后兼容用）。 */
    val target: Point get() = targets.firstOrNull() ?: Point.ZERO

    fun hasFinishCondition(): Boolean = totalClicks > 0 || totalDurationMs > 0

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
    val lastError: String? = null,
)
