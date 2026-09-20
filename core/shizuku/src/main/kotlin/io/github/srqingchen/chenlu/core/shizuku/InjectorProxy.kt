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

    fun injectTap(x: Float, y: Float, durationMs: Long): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeFloat(x)
            data.writeFloat(y)
            data.writeLong(0L) // downTimeMs 占位
            data.writeLong(durationMs)
            binder.transact(TRANSACTION_INJECT_TAP, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
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
        private const val TRANSACTION_DESTROY = 16777114
    }
}
