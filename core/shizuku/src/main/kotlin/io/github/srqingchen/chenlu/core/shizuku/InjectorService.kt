package io.github.srqingchen.chenlu.core.shizuku

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
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
                val result = runCatching { injectTap(x, y, durationMs) }
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

    /** @return RESULT_OK / RESULT_FALLBACK_CMD / RESULT_FAIL，失败详情见 lastError。 */
    @SuppressLint("PrivateApi")
    private fun injectTap(x: Float, y: Float, durationMs: Long): Int {
        lastError = ""
        val im = inputManager
        val inject = injectMethod
        if (im == null || inject == null) {
            lastError = "反射获取失败: InputManager=${im != null}, injectInputEvent=${inject != null}"
            return fallbackInputTap(x, y)
        }

        val duration = durationMs.coerceIn(1L, 500L)
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y,
            PRESSURE, SIZE, 0, 1f, 1f, 0, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val okDown = try {
            (inject.invoke(im, down, MODE_WAIT_FOR_FINISH) as? Boolean) == true
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
                (inject.invoke(im, up, MODE_WAIT_FOR_FINISH) as? Boolean) == true
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

    private val inputManager: Any? by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getDeclaredMethod("getInstance")
                .invoke(null)
        }.getOrNull()
    }

    private val injectMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getMethod(
                    "injectInputEvent",
                    MotionEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
        }.getOrNull()
    }

    companion object {
        /** 与 UserServiceArgs.version 联动：不匹配时 Shizuku 自动销毁旧服务进程。 */
        const val VERSION = 2

        const val RESULT_FAIL = 0
        const val RESULT_OK = 1
        const val RESULT_FALLBACK_CMD = 2

        /** Shizuku 约定的保留事务码：server 卸载服务时调用。 */
        const val TRANSACTION_DESTROY = 16777114

        private const val DESCRIPTOR = "io.github.srqingchen.chenlu.core.shizuku.IInjector"
        private const val TRANSACTION_VERSION = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_INJECT_TAP = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_LAST_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2

        private const val PRESSURE = 1f
        private const val SIZE = 1f

        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        private const val MODE_WAIT_FOR_FINISH = 2
    }
}
