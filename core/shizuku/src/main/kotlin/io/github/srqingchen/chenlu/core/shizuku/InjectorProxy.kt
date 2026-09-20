package io.github.srqingchen.chenlu.core.shizuku

import android.os.IBinder
import android.os.Parcel

/**
 * [InjectorService] 的客户端代理：与手写 Binder 协议配对。
 * transact 均为同步调用（flags=0），建议在 IO 线程使用。
 */
class InjectorProxy(private val binder: IBinder) {

    fun ping(): Boolean = binder.pingBinder()

    fun version(): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_VERSION, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** @return InjectorService.RESULT_OK_KERNEL / RESULT_OK / RESULT_FALLBACK_CMD / RESULT_FAIL。 */
    fun injectTap(x: Float, y: Float, durationMs: Long, screenW: Int, screenH: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeFloat(x)
            data.writeFloat(y)
            data.writeLong(0L) // downTimeMs 占位
            data.writeLong(durationMs)
            data.writeInt(screenW)
            data.writeInt(screenH)
            binder.transact(TRANSACTION_INJECT_TAP, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun lastError(): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_LAST_ERROR, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 超级岛兼容模式：block=true 切断 xmsf 联网（云端鉴权 fail-open），false 恢复。 */
    fun xmsfGate(block: Boolean): String? =
        transact2(TRANSACTION_XMSF_GATE) { it.writeInt(if (block) 1 else 0) }

    /** 经 shell 写 secure 设置开启无障碍服务。 */
    fun enableAccessibilityService(component: String): String? =
        transact2(TRANSACTION_ENABLE_ACCESSIBILITY) { it.writeString(component) }

    private fun transact2(code: Int, write: (Parcel) -> Unit): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            val resultCode = reply.readInt()
            val detail = reply.readString().orEmpty()
            if (resultCode == 0) null else "code=$resultCode: $detail"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun destroy() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_DESTROY, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private const val DESCRIPTOR = "io.github.srqingchen.chenlu.core.shizuku.IInjector"
        private const val TRANSACTION_VERSION = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_INJECT_TAP = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_LAST_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_XMSF_GATE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_ENABLE_ACCESSIBILITY = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_DESTROY = 16777114
    }
}
