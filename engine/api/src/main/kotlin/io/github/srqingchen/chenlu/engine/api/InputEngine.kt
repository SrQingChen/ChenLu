package io.github.srqingchen.chenlu.engine.api

import io.github.srqingchen.chenlu.core.model.Point
import kotlinx.coroutines.flow.StateFlow

/** 引擎静态能力描述（驱动 UI 展示与频率上限约束，数值以实测为准）。 */
data class EngineCapabilities(
    val maxTapHzApprox: Int,
    val supportsMultiTouch: Boolean,
    val note: String = "",
)

/** 引擎动态状态。 */
sealed interface EngineState {
    data object Initializing : EngineState
    data object Ready : EngineState
    data class Unavailable(val reason: String) : EngineState
}

/** 一次点击的注入参数。 */
data class TapSpec(
    val durationMs: Long = 48L,
    val pointerId: Int = 0,
)

/**
 * 输入引擎抽象。
 * M0：无障碍实现（engine:accessibility）；M2：Shizuku 实现（UserService + injectInputEvent）。
 * 实现必须保证 [tap] 在非主线程调用安全。
 */
interface InputEngine {
    val id: String
    val capabilities: EngineCapabilities
    val state: StateFlow<EngineState>

    /** 注入一次点击，返回是否成功。 */
    suspend fun tap(point: Point, spec: TapSpec = TapSpec()): Boolean

    /** 注入一次直线滑动手势（durationMs 为滑动总时长）。 */
    suspend fun swipe(from: Point, to: Point, durationMs: Long): Boolean

    /**
     * 取消在途手势。dispatchGesture 无公开取消 API，M0 采用
     * “短手势 + 间隔等待”模式，天然无在途手势；M1 手势链引入后实现。
     */
    suspend fun cancel()
}
