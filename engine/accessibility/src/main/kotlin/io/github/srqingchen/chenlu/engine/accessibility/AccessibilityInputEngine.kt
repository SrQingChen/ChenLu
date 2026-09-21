package io.github.srqingchen.chenlu.engine.accessibility

import io.github.srqingchen.chenlu.core.model.Point
import io.github.srqingchen.chenlu.core.model.TouchStroke
import io.github.srqingchen.chenlu.engine.api.EngineCapabilities
import io.github.srqingchen.chenlu.engine.api.EngineState
import io.github.srqingchen.chenlu.engine.api.InputEngine
import io.github.srqingchen.chenlu.engine.api.TapSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 无障碍手势引擎（引擎A）：免 root、开箱即用。
 * dispatchGesture 管道实测稳定 10–50 次/秒（基准数据见 docs/02-开发规划.md M1）。
 */
class AccessibilityInputEngine : InputEngine {

    override val id: String = ENGINE_ID

    override val capabilities: EngineCapabilities = EngineCapabilities(
        maxTapHzApprox = 30,
        supportsMultiTouch = true,
        note = "dispatchGesture 管道，实测稳定 10–50 次/秒",
    )

    override val state: StateFlow<EngineState> =
        ChenLuAccessibilityService.isConnected
            .map { connected ->
                if (connected) EngineState.Ready
                else EngineState.Unavailable(reason = "无障碍服务未开启")
            }
            .stateIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                started = SharingStarted.Eagerly,
                initialValue = EngineState.Initializing,
            )

    override suspend fun tap(point: Point, spec: TapSpec): Boolean =
        ChenLuAccessibilityService.dispatchTap(point.x, point.y, spec.durationMs)

    override suspend fun swipe(from: Point, to: Point, durationMs: Long): Boolean =
        ChenLuAccessibilityService.dispatchSwipe(from.x, from.y, to.x, to.y, durationMs)

    override suspend fun replay(strokes: List<TouchStroke>): Boolean =
        ChenLuAccessibilityService.dispatchStrokes(strokes)

    override suspend fun cancel() {
        // M0：短手势 + 间隔等待模式，无在途手势；M1 手势链引入后实现
    }

    companion object {
        const val ENGINE_ID = "accessibility"
    }
}
