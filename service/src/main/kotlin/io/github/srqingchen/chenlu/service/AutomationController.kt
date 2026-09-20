package io.github.srqingchen.chenlu.service

import io.github.srqingchen.chenlu.core.model.AutomationRunState
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.engine.api.EngineRegistry
import io.github.srqingchen.chenlu.engine.api.EngineState
import io.github.srqingchen.chenlu.engine.api.TapSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自动化任务控制器：UI / 悬浮球 / 通知按钮共享的单一事实源。
 * M0 提供单点连点循环；M1 起由 TaskIR 调度器接管（循环/随机抖动/多步骤）。
 */
object AutomationController {

    private const val FAILURE_THRESHOLD = 3

    private val _state = MutableStateFlow(AutomationRunState())
    val state: StateFlow<AutomationRunState> = _state.asStateFlow()

    @Volatile
    private var serviceScope: CoroutineScope? = null

    private var loopJob: Job? = null

    /** 由 AutomationService 在 onCreate 时挂载其 lifecycleScope。 */
    fun attachScope(scope: CoroutineScope) {
        serviceScope = scope
    }

    fun updateConfig(transform: (TapConfig) -> TapConfig) {
        _state.update { it.copy(config = transform(it.config)) }
    }

    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    fun start() {
        if (loopJob?.isActive == true) return
        val scope = serviceScope
        if (scope == null) {
            _state.update { it.copy(lastError = "服务未就绪，请稍后重试") }
            return
        }
        val engine = EngineRegistry.activeEngine()
        if (engine == null) {
            _state.update { it.copy(lastError = "无可用引擎：请开启无障碍服务或启动 Shizuku") }
            return
        }
        when (val s = engine.state.value) {
            is EngineState.Unavailable -> {
                _state.update { it.copy(lastError = "引擎不可用（${engine.id}）：${s.reason}") }
                return
            }
            EngineState.Initializing, EngineState.Ready -> Unit
        }
        _state.update {
            it.copy(running = true, executedCount = 0L, activeEngineId = engine.id, lastError = null)
        }
        loopJob = scope.launch {
            var consecutiveFailures = 0
            try {
                while (isActive) {
                    val live = engine.state.value
                    if (live is EngineState.Unavailable) {
                        _state.update { it.copy(running = false, lastError = "引擎掉线：${live.reason}") }
                        return@launch
                    }
                    val config = _state.value.config
                    val ok = engine.tap(config.target, TapSpec(durationMs = config.pressDurationMs))
                    if (ok) {
                        consecutiveFailures = 0
                        _state.update { it.copy(executedCount = it.executedCount + 1L, lastError = null) }
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= FAILURE_THRESHOLD) {
                            _state.update {
                                it.copy(lastError = "点击注入连续失败 ${consecutiveFailures} 次，请检查引擎状态")
                            }
                        }
                    }
                    delay(config.intervalMs.coerceAtLeast(16L))
                }
            } catch (_: CancellationException) {
                // stop() 触发，正常退出
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.update { it.copy(running = false) }
    }
}
