package io.github.srqingchen.chenlu.engine.shizuku

import io.github.srqingchen.chenlu.core.model.Point
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.core.shizuku.ShizukuState
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
import kotlinx.coroutines.withContext

/**
 * Shizuku 引擎（引擎B）：UserService + InputManager.injectInputEvent。
 * shell 级注入、零进程创建开销；理论数百 Hz（真机基准待标定）。
 */
class ShizukuInputEngine : InputEngine {

    override val id: String = ENGINE_ID

    override val capabilities: EngineCapabilities = EngineCapabilities(
        maxTapHzApprox = 200,
        supportsMultiTouch = true,
        note = "shell 级 injectInputEvent，不注册无障碍；实测频率待真机标定",
    )

    override val state: StateFlow<EngineState> =
        ShizukuManager.state
            .map { shizuku ->
                when (shizuku) {
                    ShizukuState.NotInstalled -> EngineState.Unavailable("Shizuku 未安装")
                    ShizukuState.NotRunning ->
                        EngineState.Unavailable("Shizuku 未运行（重启后需重新启动）")
                    ShizukuState.AwaitingPermission -> EngineState.Unavailable("Shizuku 待授权")
                    ShizukuState.Connecting -> EngineState.Initializing
                    is ShizukuState.Ready -> EngineState.Ready
                    is ShizukuState.Failed -> EngineState.Unavailable(shizuku.reason)
                }
            }
            .stateIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                started = SharingStarted.Eagerly,
                initialValue = EngineState.Initializing,
            )

    override suspend fun tap(point: Point, spec: TapSpec): Boolean = withContext(Dispatchers.IO) {
        val injector = ShizukuManager.injector ?: return@withContext false
        runCatching {
            injector.injectTap(point.x, point.y, spec.durationMs)
        }.getOrDefault(false)
    }

    override suspend fun cancel() {
        // 注入为瞬时 DOWN/UP，无在途手势链；M1 手势链引入后实现
    }

    companion object {
        const val ENGINE_ID = "shizuku"
    }
}
