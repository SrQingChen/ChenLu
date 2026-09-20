package io.github.srqingchen.chenlu.service

import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.core.model.AutomationRunState
import io.github.srqingchen.chenlu.core.model.TargetOrder
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
import kotlin.random.Random

/**
 * 自动化任务控制器：UI / 悬浮球 / 通知按钮共享的单一事实源。
 * 支持多目标（顺序/随机循环）；M1 起由 TaskIR 调度器接管（随机抖动/多步骤）。
 */
object AutomationController {

    private const val FAILURE_THRESHOLD = 3

    private val _state = MutableStateFlow(AutomationRunState())
    val state: StateFlow<AutomationRunState> = _state.asStateFlow()

    @Volatile
    private var serviceScope: CoroutineScope? = null

    @Volatile
    private var appContext: android.content.Context? = null

    private var loopJob: Job? = null

    /** 由 AutomationService 在 onCreate 时挂载其 lifecycleScope 与应用上下文。 */
    fun attachScope(scope: CoroutineScope) {
        serviceScope = scope
    }

    fun attachContext(context: android.content.Context) {
        appContext = context.applicationContext
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
        ChenLuLog.i("controller", "任务启动：engine=${engine.id}, config=${_state.value.config}")
        val startElapsed = android.os.SystemClock.elapsedRealtime()
        appContext?.let {
            io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.publishRunning(
                it, count = 0, config = _state.value.config, engineId = engine.id, elapsedMs = 0,
                taskName = _state.value.taskName,
            )
        }
        var lastIslandMs = 0L
        loopJob = scope.launch {
            var consecutiveFailures = 0
            var seqIndex = 0
            try {
                while (isActive) {
                    val live = engine.state.value
                    if (live is EngineState.Unavailable) {
                        ChenLuLog.e("controller", "引擎掉线，任务停止：${live.reason}")
                        _state.update { it.copy(running = false, lastError = "引擎掉线：${live.reason}") }
                        return@launch
                    }
                    val config = _state.value.config
                    val targets = config.targets
                    if (targets.isEmpty()) {
                        _state.update {
                            it.copy(running = false, lastError = "未设置目标点：拖动悬浮球或使用屏幕选点")
                        }
                        ChenLuLog.e("controller", "目标点为空，任务停止")
                        return@launch
                    }
                    val point0 = when {
                        targets.size == 1 -> targets[0]
                        config.order == TargetOrder.RANDOM -> targets[Random.nextInt(targets.size)]
                        else -> targets[seqIndex++ % targets.size]
                    }
                    // 防检测：坐标随机偏移
                    val point = if (config.jitterPx > 0) {
                        val r = config.jitterPx
                        io.github.srqingchen.chenlu.core.model.Point(
                            point0.x + (Random.nextInt(r * 2 + 1) - r),
                            point0.y + (Random.nextInt(r * 2 + 1) - r),
                        )
                    } else {
                        point0
                    }
                    val ok = engine.tap(point, TapSpec(durationMs = config.pressDurationMs))
                    if (ok) {
                        consecutiveFailures = 0
                        _state.update { it.copy(executedCount = it.executedCount + 1L, lastError = null) }
                    } else {                        consecutiveFailures++
                        if (consecutiveFailures == FAILURE_THRESHOLD) {
                            ChenLuLog.e(
                                "controller",
                                "连续注入失败 $consecutiveFailures 次（engine=${engine.id}），详见上方日志",
                            )
                            _state.update {
                                it.copy(lastError = "点击注入连续失败 ${consecutiveFailures} 次，请检查引擎状态")
                            }
                        }
                    }
                    // 防检测：时序抖动（±jitterMs）
                    val jitterDelayMs = if (config.jitterMs > 0) {
                        Random.nextInt((-config.jitterMs).toInt(), config.jitterMs.toInt() + 1)
                    } else {
                        0
                    }
                    delay((config.intervalMs + jitterDelayMs).coerceAtLeast(16L))

                    // 完成条件（总次数 / 总时长）到量自动停止
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startElapsed
                    val countNow = _state.value.executedCount
                    if (config.totalClicks > 0 && countNow >= config.totalClicks) {
                        ChenLuLog.i("controller", "已达目标次数 $countNow，自动停止")
                        stop()
                        return@launch
                    }
                    if (config.totalDurationMs > 0 && elapsed >= config.totalDurationMs) {
                        ChenLuLog.i("controller", "已达目标时长，自动停止")
                        stop()
                        return@launch
                    }

                    // 超级岛 1Hz 节流刷新
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (nowMs - lastIslandMs >= 1000L) {
                        lastIslandMs = nowMs
                        appContext?.let { ctx ->
                            io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.publishRunning(
                                ctx,
                                count = _state.value.executedCount,
                                config = config,
                                engineId = engine.id,
                                elapsedMs = elapsed,
                                taskName = _state.value.taskName,
                                error = _state.value.lastError,
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                // stop() 触发，正常退出
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        ChenLuLog.i("controller", "任务停止（已执行 ${_state.value.executedCount} 次）")
        loopJob?.cancel()
        loopJob = null
        _state.update { it.copy(running = false) }
        appContext?.let { io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.onTaskStopped(it) }
    }

    /** 面板“间隔±”：步进 25ms。 */
    fun adjustInterval(deltaMs: Long) {
        updateConfig { it.copy(intervalMs = (it.intervalMs + deltaMs).coerceIn(16L, 2000L)) }
        ChenLuLog.i("controller", "间隔调整为 ${_state.value.config.intervalMs}ms")
    }

    /** 立即刷新岛（面板间隔调整后反馈）。 */
    fun refreshIsland() {
        val ctx = appContext ?: return
        val running = _state.value.running
        if (running) {
            io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.publishRunning(
                ctx,
                count = _state.value.executedCount,
                config = _state.value.config,
                engineId = _state.value.activeEngineId,
                elapsedMs = 0,
                taskName = _state.value.taskName,
                error = _state.value.lastError,
            )
        } else if (io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.idleEnabled &&
            !io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.appForeground
        ) {
            io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.publishIdle(ctx)
        }
    }

    /** 记录当前任务名（保存/加载/换任务时设置，岛与 UI 展示用）。 */
    fun updateTaskName(name: String?) {
        _state.update { it.copy(taskName = name) }
    }

    /** 岛上「换任务」：在任务库中循环切换（step 通常为 ±1）。 */
    fun switchTask(step: Int) {
        val tasks = io.github.srqingchen.chenlu.core.data.TaskRepository.tasks.value
        if (tasks.isEmpty()) {
            ChenLuLog.w("controller", "任务库为空，无法切换")
            return
        }
        taskCursor = ((taskCursor + step) % tasks.size + tasks.size) % tasks.size
        val task = tasks[taskCursor]
        val loaded = task.config
        if (loaded == null) {
            ChenLuLog.e("controller", "任务配置损坏: ${task.name}")
            return
        }
        updateConfig { existing ->
            loaded.copy(targets = loaded.targets.ifEmpty { existing.targets })
        }
        updateTaskName(task.name)
        ChenLuLog.i("controller", "切换任务: ${task.name}")
        refreshIsland()
    }

    @Volatile
    private var taskCursor = 0
}
