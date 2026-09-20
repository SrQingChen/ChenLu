package io.github.srqingchen.chenlu.engine.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 无障碍服务（引擎A宿主）。仅声明手势能力，不读取窗口内容。
 */
class ChenLuAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        ChenLuLog.i("accessibility", "服务已连接，手势注入就绪")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _isConnected.value = false
        instance = null
        ChenLuLog.w("accessibility", "服务已解绑（被系统或用户关闭）")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _isConnected.value = false
        instance = null
        ChenLuLog.w("accessibility", "服务已销毁")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: ChenLuAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val mainHandler = Handler(Looper.getMainLooper())

        /** 注入一次点击手势，挂起至手势完成，返回是否成功。 */
        suspend fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean {
            val service = instance ?: run {
                ChenLuLog.e("accessibility", "服务实例为空：无障碍未开启或已被系统回收")
                return false
            }
            val path = Path().apply {
                moveTo(x, y)
                // 微小位移避免零长度 stroke 在部分设备上被立即取消
                lineTo(x, y + 0.1f)
            }
            val stroke = GestureDescription.StrokeDescription(
                path,
                0L,
                durationMs.coerceIn(1L, 60_000L),
            )
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return suspendCancellableCoroutine { cont ->
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            ChenLuLog.w(
                                "accessibility",
                                "手势被取消（x=$x,y=$y）：常见原因=用户手指在屏/目标界面拒绝/服务被暂停",
                            )
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    mainHandler,
                )
                if (!dispatched && cont.isActive) {
                    ChenLuLog.e("accessibility", "dispatchGesture 提交失败（服务状态异常）")
                    cont.resume(false)
                }
            }
        }
    }
}
