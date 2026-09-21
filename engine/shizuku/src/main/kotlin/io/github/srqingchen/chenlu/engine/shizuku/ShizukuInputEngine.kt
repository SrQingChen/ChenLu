package io.github.srqingchen.chenlu.engine.shizuku

import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.core.model.Point
import io.github.srqingchen.chenlu.core.model.TouchStroke
import io.github.srqingchen.chenlu.core.shizuku.InjectorService
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.core.shizuku.ShizukuState
import io.github.srqingchen.chenlu.engine.api.EngineCapabilities
import io.github.srqingchen.chenlu.engine.api.EngineState
import io.github.srqingchen.chenlu.engine.api.InputEngine
import io.github.srqingchen.chenlu.engine.api.TapSpec
import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Shizuku 引擎（引擎B）：三级注入链（内核 /dev/input 直写 → injectInputEvent → input tap）。
 * 内核级事件走完整输入管线（「显示点按操作」可见），仿真度与反检测最优。
 */
class ShizukuInputEngine(private val appContext: Context? = null) : InputEngine {

    override val id: String = ENGINE_ID

    override val capabilities: EngineCapabilities = EngineCapabilities(
        maxTapHzApprox = 200,
        supportsMultiTouch = true,
        note = "shell 级三级注入（内核直写优先），实测频率待真机标定",
    )

    /** 屏幕物理尺寸（内核级坐标映射用），应用进程侧计算。 */
    private val screenBounds: Rect? by lazy {
        appContext?.let { ctx ->
            runCatching {
                ctx.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            }.getOrNull()
        }
    }

    /** 注入模式日志去重：仅在切换时记录一条。 */
    @Volatile
    private var lastMode = 0

    override val state: StateFlow<EngineState> =
        ShizukuManager.state
            .onEach { ChenLuLog.i("shizuku", "通道状态 -> $it") }
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
        val injector = ShizukuManager.injector
        if (injector == null) {
            ChenLuLog.e("shizuku", "注入服务未连接（injector=null），当前状态=${ShizukuManager.state.value}")
            return@withContext false
        }
        val code = runCatching {
            val bounds = screenBounds
            injector.injectTap(
                point.x, point.y, spec.durationMs,
                bounds?.width() ?: 0, bounds?.height() ?: 0,
            )
        }.getOrElse { t ->
            ChenLuLog.e("shizuku", "Binder 调用异常: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
        when (code) {
            InjectorService.RESULT_OK_KERNEL, InjectorService.RESULT_OK -> {
                if (code != lastMode) {
                    val reason = runCatching { injector.lastError() }.getOrDefault("")
                    ChenLuLog.i(
                        "shizuku",
                        if (code == InjectorService.RESULT_OK_KERNEL) {
                            "内核级注入已启用（/dev/input 直写，触摸显示可见）"
                        } else {
                            "注入模式：injectInputEvent。内核直写不可用原因: $reason"
                        },
                    )
                    lastMode = code
                }
                true
            }
            InjectorService.RESULT_FALLBACK_CMD -> {
                ChenLuLog.w(
                    "shizuku",
                    "已降级为 input 命令注入（较慢）。原因: ${runCatching { injector.lastError() }.getOrDefault("?")}",
                )
                true
            }
            else -> {
                val detail = runCatching { injector.lastError() }.getOrDefault("Binder 调用失败")
                ChenLuLog.e("shizuku", "注入失败 code=$code: $detail")
                false
            }
        }
    }

    override suspend fun swipe(from: Point, to: Point, durationMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val injector = ShizukuManager.injector
            if (injector == null) {
                ChenLuLog.e("shizuku", "滑动失败：注入服务未连接")
                return@withContext false
            }
            val bounds = screenBounds
            val code = runCatching {
                injector.injectSwipe(
                    from.x, from.y, to.x, to.y, durationMs,
                    bounds?.width() ?: 0, bounds?.height() ?: 0,
                )
            }.getOrElse { -1 }
            when (code) {
                InjectorService.RESULT_OK -> true
                InjectorService.RESULT_FALLBACK_CMD -> {
                    ChenLuLog.w("shizuku", "滑动已降级 input swipe。原因: ${runCatching { injector.lastError() }.getOrDefault("?")}")
                    true
                }
                else -> {
                    ChenLuLog.e("shizuku", "滑动失败 code=$code: ${runCatching { injector.lastError() }.getOrDefault("")}")
                    false
                }
            }
        }

    /** 回放录制轨迹：按录制节奏逐事件注入（多指录制仅回放第一指，多指需无障碍引擎）。 */
    override suspend fun replay(strokes: List<TouchStroke>): Boolean = withContext(Dispatchers.IO) {
        val injector = ShizukuManager.injector
        if (injector == null) {
            ChenLuLog.e("shizuku", "回放失败：注入服务未连接")
            return@withContext false
        }
        if (strokes.isEmpty()) return@withContext false
        if (strokes.size > 1) {
            ChenLuLog.w("shizuku", "录制含 ${strokes.size} 指：Shizuku 回放仅支持第一指（多指需无障碍引擎）")
        }
        val pts = strokes.first().points
        if (pts.size < 2) return@withContext false
        val t0 = pts.first().t
        val downTime = android.os.SystemClock.uptimeMillis()
        val startElapsed = android.os.SystemClock.elapsedRealtime()
        var ok = injector.injectEvent(
            android.view.MotionEvent.ACTION_DOWN, pts[0].x, pts[0].y, downTime, downTime,
        ) == 1
        for (i in 1 until pts.size) {
            if (!ok) break
            val p = pts[i]
            val wait = startElapsed + (p.t - t0) - android.os.SystemClock.elapsedRealtime()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            ok = injector.injectEvent(
                android.view.MotionEvent.ACTION_MOVE, p.x, p.y, downTime,
                android.os.SystemClock.uptimeMillis(),
            ) == 1
        }
        if (ok) {
            val last = pts.last()
            ok = injector.injectEvent(
                android.view.MotionEvent.ACTION_UP, last.x, last.y, downTime,
                android.os.SystemClock.uptimeMillis(),
            ) == 1
        }
        if (!ok) {
            val detail = runCatching { injector.lastError() }.getOrDefault("")
            ChenLuLog.e("shizuku", "轨迹回放中断: $detail")
        }
        ok
    }

    override suspend fun cancel() {
        // 注入为瞬时 DOWN/UP，无在途手势链；M1 手势链引入后实现
    }

    companion object {
        const val ENGINE_ID = "shizuku"
    }
}
