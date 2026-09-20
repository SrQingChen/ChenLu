package io.github.srqingchen.chenlu.service.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * 悬浮控制球：拖动 = 移动点击目标（准星跟手）；松手后吸附屏幕边缘；单击启停任务。
 * 纯 View 自绘（不依赖 Compose），保证轻量与低延迟。
 */
class ControlBallView(context: Context) : View(context) {

    var onToggle: (() -> Unit)? = null

    /** 拖动中回调（球心的屏幕物理坐标，Gravity TOP|START 体系）。 */
    var onCenterChanged: ((centerX: Float, centerY: Float) -> Unit)? = null

    /** 抬手回调（dragged = 本次触摸是否为拖动；单击时为 false，由 performClick 处理）。 */
    var onReleased: ((dragged: Boolean) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragged = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, cx * 0.82f, fillPaint)
        canvas.drawCircle(cx, cy, cx * 0.95f, ringPaint)
        val r = cx * 0.38f
        canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragged && (abs(dx) > slop || abs(dy) > slop)) dragged = true
                if (dragged) {
                    moveBy(dx, dy)
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                onReleased?.invoke(dragged)
                if (!dragged) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.invoke()
        return true
    }

    private fun moveBy(dx: Float, dy: Float) {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.updateViewLayout(this, lp)
        onCenterChanged?.invoke(lp.x + width / 2f, lp.y + height / 2f)
    }

    companion object {
        private const val ACCENT = 0xFF00897B.toInt()
    }
}
