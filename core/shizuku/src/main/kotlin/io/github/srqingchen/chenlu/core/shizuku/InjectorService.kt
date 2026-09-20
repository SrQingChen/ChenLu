package io.github.srqingchen.chenlu.core.shizuku

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.Keep

/**
 * 运行在 Shizuku server 侧（shell uid 2000 / root）的注入服务。
 * shell 身份持有 INJECT_EVENTS，直接调用 InputManager.injectInputEvent
 * （与系统 input 命令同一条特权注入路径，零进程创建开销）。
 *
 * 手写 Binder 协议（不经 AIDL 工具链，规避非 ASCII 路径下的编码缺陷）；
 * UserService 进程不受非 SDK 接口限制，反射调用隐藏 API 即可。
 */
@Keep
class InjectorService : Binder() {

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
                val ok = injectTap(x, y, durationMs)
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                return true
            }

            TRANSACTION_DESTROY -> {
                reply?.writeNoException()
                // 延迟退出，确保回复先送达
                Thread {
                    runCatching { Thread.sleep(100) }
                    System.exit(0)
                }.start()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    @SuppressLint("PrivateApi")
    private fun injectTap(x: Float, y: Float, durationMs: Long): Boolean {
        val im = inputManager ?: return false
        val inject = injectMethod ?: return false
        val duration = durationMs.coerceIn(1L, 2_000L)
        val now = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, x, y,
            PRESSURE, SIZE, 0, 1f, 1f, 0, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val okDown = runCatching {
            inject.invoke(im, down, MODE_WAIT_FOR_FINISH) as? Boolean ?: false
        }.getOrDefault(false)
        down.recycle()

        val up = MotionEvent.obtain(
            now, now + duration, MotionEvent.ACTION_UP, x, y,
            PRESSURE, SIZE, 0, 1f, 1f, 0, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val okUp = runCatching {
            inject.invoke(im, up, MODE_WAIT_FOR_FINISH) as? Boolean ?: false
        }.getOrDefault(false)
        up.recycle()

        return okDown && okUp
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
        const val VERSION = 1

        /** Shizuku 约定的保留事务码：server 卸载服务时调用。 */
        const val TRANSACTION_DESTROY = 16777114

        private const val DESCRIPTOR = "io.github.srqingchen.chenlu.core.shizuku.IInjector"
        private const val TRANSACTION_VERSION = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_INJECT_TAP = IBinder.FIRST_CALL_TRANSACTION + 1

        private const val PRESSURE = 1f
        private const val SIZE = 1f

        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        private const val MODE_WAIT_FOR_FINISH = 2
    }
}
