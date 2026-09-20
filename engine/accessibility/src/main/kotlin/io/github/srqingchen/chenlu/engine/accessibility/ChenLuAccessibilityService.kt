package io.github.srqingchen.chenlu.engine.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
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
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _isConnected.value = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _isConnected.value = false
        instance = null
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
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
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
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    mainHandler,
                )
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        }
    }
}
