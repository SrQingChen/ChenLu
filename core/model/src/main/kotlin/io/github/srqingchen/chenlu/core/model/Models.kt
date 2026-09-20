package io.github.srqingchen.chenlu.core.model

/** 屏幕坐标（物理像素）。 */
data class Point(val x: Float, val y: Float) {
    companion object {
        val ZERO = Point(0f, 0f)
    }
}

/** M0 单点连点配置；M1 起扩展为完整 TaskIR。 */
data class TapConfig(
    val target: Point = Point.ZERO,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val pressDurationMs: Long = DEFAULT_PRESS_MS,
) {
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
    val lastError: String? = null,
)
