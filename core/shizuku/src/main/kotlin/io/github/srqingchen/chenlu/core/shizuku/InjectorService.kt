package io.github.srqingchen.chenlu.core.shizuku

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku server 侧（shell uid 2000 / root）的注入服务。
 *
 * 注入优先级：InputManager.injectInputEvent（反射，shell 持有 INJECT_EVENTS），
 * 失败时自动降级执行 `input tap` 子进程（慢一个量级但兼容性最好）。
 * 失败详情写入 [lastError]，客户端经 TRANSACTION_LAST_ERROR 取回展示/记日志。
 *
 * 手写 Binder 协议（不经 AIDL 工具链，规避非 ASCII 路径下的编码缺陷）；
 * UserService 进程不受非 SDK 接口限制。
 */
@Keep
class InjectorService : Binder() {

    @Volatile
    private var lastError: String = ""

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_VERSION -> {
                reply?.writeNoException()
                reply?.writeInt(VERSION)
                return true
            }

            TRANSACTION_INJECT_TAP -> {
                data.enforceInterface(DESCRIPTOR)
                val x = data.readFloat()
                val y = data.readFloat()
                data.readLong() // downTimeMs：节奏由调用方节流，此处忽略
                val durationMs = data.readLong()
                val screenW = data.readInt()
                val screenH = data.readInt()
                val result = runCatching { injectTap(x, y, screenW, screenH, durationMs) }
                    .getOrElse { RESULT_FAIL }
                reply?.writeNoException()
                reply?.writeInt(result)
                return true
            }

            TRANSACTION_LAST_ERROR -> {
                reply?.writeNoException()
                reply?.writeString(lastError)
                return true
            }

            TRANSACTION_INJECT_SWIPE -> {
                data.enforceInterface(DESCRIPTOR)
                val fromX = data.readFloat()
                val fromY = data.readFloat()
                val toX = data.readFloat()
                val toY = data.readFloat()
                val durationMs = data.readLong()
                val screenW = data.readInt()
                val screenH = data.readInt()
                val result = runCatching { injectSwipe(fromX, fromY, toX, toY, screenW, screenH, durationMs) }
                    .getOrElse { RESULT_FAIL }
                reply?.writeNoException()
                reply?.writeInt(result)
                return true
            }

            TRANSACTION_XMSF_GATE -> {
                data.enforceInterface(DESCRIPTOR)
                val block = data.readInt() != 0
                val (code, out) = runCatching { xmsfGate(block) }.getOrElse { -1 to it.message.orEmpty() }
                reply?.writeNoException()
                reply?.writeInt(code)
                reply?.writeString(out)
                return true
            }

            TRANSACTION_ENABLE_ACCESSIBILITY -> {
                data.enforceInterface(DESCRIPTOR)
                val pkg = data.readString().orEmpty()
                val component = data.readString().orEmpty()
                val (code, out) = runCatching { enableAccessibility(pkg, component) }
                    .getOrElse { -1 to it.message.orEmpty() }
                reply?.writeNoException()
                reply?.writeInt(code)
                reply?.writeString(out)
                return true
            }

            TRANSACTION_DESTROY -> {
                reply?.writeNoException()
                Thread {
                    runCatching { Thread.sleep(100) }
                    System.exit(0)
                }.start()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private val kernelInjector = KernelTouchInjector()

    /**
     * 三级注入链：内核级（/dev/input 直写，最仿真）→ injectInputEvent → input tap。
     * @return RESULT_OK_KERNEL / RESULT_OK / RESULT_FALLBACK_CMD / RESULT_FAIL，失败详情见 lastError。
     */
    @SuppressLint("PrivateApi")
    private fun injectTap(x: Float, y: Float, screenW: Int, screenH: Int, durationMs: Long): Int {
        lastError = ""

        // 一级：内核直写（事件走完整输入管线，「显示点按操作」可见）
        val kernelError = runCatching {
            kernelInjector.tap(x, y, screenW, screenH, durationMs.coerceIn(1L, 500L))
        }.getOrElse { "内核注入异常: ${it.message}" }
        if (kernelError == null) return RESULT_OK_KERNEL
        if (lastError.isEmpty()) {
            lastError = "内核级注入不可用（$kernelError），已回退 injectInputEvent"
        }

        // 二级：InputManager.injectInputEvent
        val im = inputManager
        if (im == null || injectMethod == null) {
            lastError = buildString {
                append("反射获取失败: InputManager=${im != null}")
                if (injectMethod == null) {
                    append("; injectInputEvent 未匹配到可用签名")
                    append(
                        if (injectCandidates.isEmpty()) {
                            "（方法不存在）"
                        } else {
                            "，候选=" + injectCandidates.joinToString("; ") { it.toGenericString() }
                        },
                    )
                }
            }
            return fallbackInputTap(x, y)
        }

        val duration = durationMs.coerceIn(1L, 500L)
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y,
            PRESSURE, SIZE, 0, 1f, 1f, 0, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val okDown = try {
            invokeInject(im, down)
        } catch (t: Throwable) {
            lastError = "injectInputEvent(DOWN) 异常: ${t.javaClass.simpleName}: ${t.message}"
            false
        }
        down.recycle()

        if (okDown) {
            if (duration >= 16) SystemClock.sleep(duration)
            val up = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y,
                PRESSURE, SIZE, 0, 1f, 1f, 0, 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val okUp = try {
                invokeInject(im, up)
            } catch (t: Throwable) {
                lastError = "injectInputEvent(UP) 异常: ${t.javaClass.simpleName}: ${t.message}"
                false
            }
            up.recycle()
            if (okUp) return RESULT_OK
            if (lastError.isEmpty()) {
                lastError =
                    "injectInputEvent 返回 false（小米/澎湃常见原因：开发者选项「USB 调试（安全设置）」未开启）"
            }
        }
        return fallbackInputTap(x, y)
    }

    /** 兜底：设备内直接执行 input tap（同为 shell 权限通道，无 adb 传输开销）。 */
    private fun fallbackInputTap(x: Float, y: Float): Int = try {
        val process = ProcessBuilder(
            "input", "tap", x.toInt().toString(), y.toInt().toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (finished && process.exitValue() == 0) {
            if (lastError.isNotEmpty()) lastError += " | "
            lastError += "已降级 input tap（injectInputEvent 不可用）"
            RESULT_FALLBACK_CMD
        } else {
            val exit = if (finished) process.exitValue().toString() else "timeout"
            if (lastError.isNotEmpty()) lastError += " | "
            lastError += "input tap 失败: exit=$exit out=$output"
            RESULT_FAIL
        }
    } catch (t: Throwable) {
        if (lastError.isNotEmpty()) lastError += " | "
        lastError += "input tap 异常: ${t.javaClass.simpleName}: ${t.message}"
        RESULT_FAIL
    }

    /**
     * 直线滑动：injectInputEvent 逐点插值注入（约 16ms 步进）；
     * 失败回退 `input swipe` 命令。
     */
    @SuppressLint("PrivateApi")
    private fun injectSwipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        screenW: Int,
        screenH: Int,
        durationMs: Long,
    ): Int {
        lastError = ""
        val duration = durationMs.coerceIn(50L, 3_000L)
        val im = inputManager
        if (im != null && injectMethod != null) {
            val downTime = SystemClock.uptimeMillis()
            val steps = (duration / 16L).coerceIn(2L, 120L).toInt()
            val okDown = runCatching {
                invokeInject(im, motion(fromX, fromY, MotionEvent.ACTION_DOWN, downTime, downTime))
            }.getOrDefault(false)
            if (okDown) {
                val stepMs = duration / steps
                var allOk = true
                for (i in 1 until steps) {
                    if (stepMs >= 8L) SystemClock.sleep(stepMs)
                    val t = i.toFloat() / steps
                    val ok = runCatching {
                        invokeInject(
                            im,
                            motion(
                                fromX + (toX - fromX) * t,
                                fromY + (toY - fromY) * t,
                                MotionEvent.ACTION_MOVE,
                                downTime,
                                SystemClock.uptimeMillis(),
                            ),
                        )
                    }.getOrDefault(false)
                    if (!ok) {
                        allOk = false
                        lastError = "injectInputEvent(MOVE) 失败于步 $i"
                        break
                    }
                }
                if (allOk) {
                    val okUp = runCatching {
                        invokeInject(im, motion(toX, toY, MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis()))
                    }.getOrDefault(false)
                    if (okUp) return RESULT_OK
                    lastError = "injectInputEvent(UP) 失败"
                }
            } else if (lastError.isEmpty()) {
                lastError = "injectInputEvent(DOWN) 失败"
            }
        } else {
            lastError = "反射不可用"
        }
        return fallbackInputSwipe(fromX, fromY, toX, toY, duration)
    }

    private fun motion(x: Float, y: Float, action: Int, downTime: Long, eventTime: Long): MotionEvent =
        MotionEvent.obtain(
            downTime, eventTime, action, x, y,
            PRESSURE, SIZE, 0, 1f, 1f, 0, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

    /** 兜底：设备内直接执行 input swipe。 */
    private fun fallbackInputSwipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Int = try {
        val process = ProcessBuilder(
            "input", "swipe",
            fromX.toInt().toString(), fromY.toInt().toString(),
            toX.toInt().toString(), toY.toInt().toString(),
            durationMs.toInt().toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (finished && process.exitValue() == 0) {
            if (lastError.isNotEmpty()) lastError += " | "
            lastError += "已降级 input swipe"
            RESULT_FALLBACK_CMD
        } else {
            if (lastError.isNotEmpty()) lastError += " | "
            lastError += "input swipe 失败: out=$output"
            RESULT_FAIL
        }
    } catch (t: Throwable) {
        if (lastError.isNotEmpty()) lastError += " | "
        lastError += "input swipe 异常: ${t.message}"
        RESULT_FAIL
    }

    private fun execCommand(timeoutSec: Long = 3, vararg cmd: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        (if (finished) process.exitValue() else -1) to output
    } catch (t: Throwable) {
        -1 to (t.message ?: t.javaClass.simpleName)
    }

    /**
     * 经 shell 开启无障碍：先尝试解除侧载应用的受限设置（Android 13+ 会拒绝绑定
     * 受限应用的服务），再合并写入并回读校验。
     */
    private fun enableAccessibility(pkg: String, component: String): Pair<Int, String> {
        if (component.isBlank()) return -1 to "component 为空"
        // 解除受限设置（appop；失败不阻断，部分 ROM 无此 appop）
        val (oc, oo) = execCommand(3, "appops", "set", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow")
        val (gc, current) = execCommand(3, "settings", "get", "secure", "enabled_accessibility_services")
        if (gc != 0) return gc to "读取失败: $current（appops exit=$oc out=$oo）"
        val existing = current.trim().trim('"').split(':').filter { it.isNotBlank() }
        if (component !in existing) {
            val (pc, po) = execCommand(
                3, "settings", "put", "secure", "enabled_accessibility_services",
                (existing + component).joinToString(":"),
            )
            if (pc != 0) return pc to po
        }
        val (ec, eo) = execCommand(3, "settings", "put", "secure", "accessibility_enabled", "1")
        if (ec != 0) return ec to eo
        // 回读校验：被系统回滚时报 -2
        val (rc, rv) = execCommand(3, "settings", "get", "secure", "enabled_accessibility_services")
        return if (rv.contains(component)) {
            0 to "readback OK（appops exit=$oc）"
        } else {
            -2 to "写入疑似被系统回滚，readback=$rv"
        }
    }

    /** 超级岛兼容模式：临时切断/恢复小米推送服务（xmsf）联网，令云端白名单鉴权 fail-open。 */
    private fun xmsfGate(block: Boolean): Pair<Int, String> {
        val flag = if (block) "false" else "true"
        return execCommand(4, "cmd", "connectivity", "set-package-networking-enabled", flag, "com.xiaomi.xmsf")
    }

    private val inputManager: Any? by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getDeclaredMethod("getInstance")
                .invoke(null)
        }.getOrNull()
    }

    /** 全部 injectInputEvent 重载（含厂商扩展），用于自适应匹配与诊断回传。 */
    private val injectCandidates: List<java.lang.reflect.Method> by lazy {
        runCatching {
            val cls = Class.forName("android.hardware.input.InputManager")
            (cls.methods + cls.declaredMethods).distinctBy { it.toGenericString() }
                .filter { it.name == "injectInputEvent" }
        }.getOrDefault(emptyList())
    }

    /**
     * 自适应挑选签名：AOSP 为 (InputEvent, int)；
     * 兼容厂商新增尾参的 (InputEvent, int, int) 变体。
     */
    private val injectMethod: java.lang.reflect.Method? by lazy {
        injectCandidates.firstOrNull { m ->
            m.parameterCount == 2 &&
                InputEvent::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: injectCandidates.firstOrNull { m ->
            m.parameterCount == 3 &&
                InputEvent::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                m.parameterTypes[2] == Int::class.javaPrimitiveType
        }
    }

    /** 按匹配到的签名调用注入；签名未知返回 false。 */
    private fun invokeInject(im: Any, event: MotionEvent): Boolean {
        val method = injectMethod ?: return false
        val result = when (method.parameterCount) {
            2 -> method.invoke(im, event, MODE_WAIT_FOR_FINISH)
            3 -> method.invoke(im, event, MODE_WAIT_FOR_FINISH, 0)
            else -> return false
        }
        return result as? Boolean ?: false
    }

    companion object {
        /** 与 UserServiceArgs.version 联动：不匹配时 Shizuku 自动销毁旧服务进程。 */
        const val VERSION = 7

        const val RESULT_FAIL = 0
        const val RESULT_OK = 1
        const val RESULT_FALLBACK_CMD = 2
        const val RESULT_OK_KERNEL = 3

        /** Shizuku 约定的保留事务码：server 卸载服务时调用。 */
        const val TRANSACTION_DESTROY = 16777114

        private const val DESCRIPTOR = "io.github.srqingchen.chenlu.core.shizuku.IInjector"
        private const val TRANSACTION_VERSION = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_INJECT_TAP = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_LAST_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_XMSF_GATE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_ENABLE_ACCESSIBILITY = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_INJECT_SWIPE = IBinder.FIRST_CALL_TRANSACTION + 5

        private const val PRESSURE = 1f
        private const val SIZE = 1f

        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        private const val MODE_WAIT_FOR_FINISH = 2
    }
}
