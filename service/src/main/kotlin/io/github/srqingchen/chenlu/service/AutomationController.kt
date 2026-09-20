package io.github.srqingchen.chenlu.service

import io.github.srqingchen.chenlu.core.model.AutomationRunState
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.engine.api.EngineRegistry
import io.github.srqingchen.chenlu.engine.api.TapSpec
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
        val engine = EngineRegistry.active()
        if (scope == null || engine == null) {
            _state.update {
                it.copy(lastError = if (scope == null) "服务未就绪" else "无可用引擎（请开启无障碍）")
            }
            return
        }
        _state.update { it.copy(running = true, executedCount = 0L, lastError = null) }
        loopJob = scope.launch {
            while (isActive) {
                val config = _state.value.config
                val ok = engine.tap(config.target, TapSpec(durationMs = config.pressDurationMs))
                _state.update { s ->
                    if (ok) s.copy(executedCount = s.executedCount + 1L)
                    else s.copy(lastError = "点击注入失败")
                }
                delay(config.intervalMs.coerceAtLeast(16L))
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.update { it.copy(running = false) }
    }
}
