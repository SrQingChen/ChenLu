package io.github.srqingchen.chenlu.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.WindowManager
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** getevent 原始事件（坐标已映射为屏幕像素）。 */
data class RawEvent(val t: Long, val type: Int, val code: Int, val value: Int)

/**
 * Shizuku 通道管理：Binder 生命周期、权限三段式、UserService 绑定、录制控制。
 * 状态对外以 [state] 暴露，由 app 启动时调用 [start] 开始监听。
 */
object ShizukuManager {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 10114

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    @Volatile
    var injector: InjectorProxy? = null
        private set

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val proxy = InjectorProxy(binder)
                injector = proxy
                _state.value = ShizukuState.Ready(Shizuku.getUid())
            } else {
                _state.value = ShizukuState.Failed("注入服务返回无效 Binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
            _state.value = ShizukuState.NotRunning
        }
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, InjectorService::class.java.name),
        )
            .tag("chenlu-injector")
            .version(InjectorService.VERSION)
            .daemon(false)
            .processNameSuffix("injector")
            .debuggable(false)

    /** App 启动时调用：无条件注册 Binder/权限监听（幂等），随后评估一次状态。 */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
        }
        // 监听无条件注册：即使当时未安装/未运行，后续 Shizuku 启动也能收到回调
        Shizuku.addBinderReceivedListenerSticky { onBinderAlive() }
        Shizuku.addBinderDeadListener {
            injector = null
            _state.value = ShizukuState.NotRunning
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) onBinderAlive()
        }
        refresh()
    }

    /**
     * 重新评估状态（应用回到前台/用户点重试时调用）：
     * 重查安装（可见性/晚安装场景）并尝试即时 ping。
     */
    fun refresh() {
        val ctx = appContext ?: return
        val info = runCatching {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        }.getOrNull()
        if (info == null) {
            ChenLuLog.w(
                "shizuku",
                "包检查未见 $SHIZUKU_PACKAGE（确认已安装官方版 v11+；旧版本请到 GitHub Releases 更新）",
            )
            _state.value = ShizukuState.NotInstalled
            return
        }
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        ChenLuLog.i("shizuku", "检测到 Shizuku ${info.versionName}，binder=$binderAlive")
        if (binderAlive) {
            onBinderAlive()
        } else if (_state.value !is ShizukuState.Ready) {
            _state.value = ShizukuState.NotRunning
        }
    }

    /** 由 UI 调用：请求用户授权（需前台）。 */
    fun requestPermission() {
        val ctx = appContext ?: return
        runCatching {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }.onFailure {
            // 用户“拒绝且不再询问”等场景：引导去 Shizuku 应用内手动开启
            openShizukuApp(ctx)
        }
    }

    /** 打开 Shizuku 应用（启动 server / 手动授权）。 */
    fun openShizukuApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /** 立即尝试绑定（UI“重试”按钮）。 */
    fun rebind() {
        refresh()
    }

    /** 经 Shizuku（shell）一键开启无障碍服务（含解除受限设置与回读校验）；返回 null 表示成功。 */
    fun enableAccessibilityService(pkg: String, component: String): String? {
        val injector = injector ?: return "注入服务未连接"
        return runCatching { injector.enableAccessibilityService(pkg, component) }
            .getOrElse { it.message }
            ?.also { ChenLuLog.e("shizuku", "一键开启无障碍失败: $it") }
            ?: ChenLuLog.i("shizuku", "一键开启无障碍写入成功: $component").let { null }
    }

    /** 超级岛兼容模式：临时切断/恢复 xmsf 联网；返回 null 表示成功。 */
    fun xmsfGate(block: Boolean): String? {
        val injector = injector ?: return "注入服务未连接"
        return runCatching { injector.xmsfGate(block) }.getOrElse { it.message }
    }

    /** 开始录制真实触屏事件（getevent -t）。返回 null 表示成功。 */
    fun startRecording(): String? {
        val injector = injector ?: return "注入服务未连接"
        val ctx = appContext ?: return "上下文未初始化"
        val bounds = runCatching {
            ctx.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        }.getOrNull()
        val err = runCatching {
            injector.recordStart(bounds?.width() ?: 0, bounds?.height() ?: 0)
        }.getOrElse { it.message }
        if (err == null) {
            _isRecording.value = true
            ChenLuLog.i("recorder", "getevent 录制已开始")
        } else {
            ChenLuLog.e("recorder", "录制启动失败: $err")
        }
        return err
    }

    /** 停止录制并取回原始事件。 */
    fun stopRecording(): List<RawEvent> {
        val injector = injector
        _isRecording.value = false
        if (injector == null) return emptyList()
        val events = runCatching { injector.recordStop() }.getOrElse { emptyList() }
        ChenLuLog.i("recorder", "录制结束：${events.size} 个原始事件")
        return events
    }

    private fun onBinderAlive() {
        val ctx = appContext ?: return
        if (!Shizuku.pingBinder()) {
            _state.value = ShizukuState.NotRunning
            return
        }
        if (Shizuku.isPreV11()) {
            _state.value = ShizukuState.Failed("Shizuku 版本过旧（需 v11+）")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _state.value = ShizukuState.AwaitingPermission
            return
        }
        if (injector != null) {
            _state.value = ShizukuState.Ready(Shizuku.getUid())
            return
        }
        _state.value = ShizukuState.Connecting
        val result = runCatching {
            Shizuku.bindUserService(userServiceArgs(ctx), userServiceConnection)
        }
        if (result.isFailure) {
            _state.value = ShizukuState.Failed("绑定注入服务失败：${result.exceptionOrNull()?.message}")
        }
    }
}
