package io.github.srqingchen.chenlu.service.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 十字准星：仅作为点击目标的可视化标记（多点模式下显示序号）。
 * 所在窗口使用 FLAG_NOT_TOUCHABLE（触摸穿透），注入的点击事件不会命中本控件。
 */
class CrosshairView(context: Context) : View(context) {

    /** 多点模式下显示的序号（从 1 开始），单点为 null。 */
    var label: String? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 137, 123) // ChenLuAccent
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 105, 92) // DewPrimaryLight
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 0, 0, 0)
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var pulseStart = 0L

    /** 命中点击时触发扩散脉冲动画（约 350ms）。 */
    fun pulse() {
        pulseStart = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = width * 0.42f
        // 底晕保证在浅色背景上可见
        canvas.drawCircle(cx, cy, radius + 6f, haloPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        // 十字四段，中心留空不遮挡目标
        val gap = width * 0.10f
        val reach = width * 0.5f
        canvas.drawLine(cx - reach, cy, cx - gap, cy, crossPaint)
        canvas.drawLine(cx + gap, cy, cx + reach, cy, crossPaint)
        canvas.drawLine(cx, cy - reach, cx, cy - gap, crossPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + reach, crossPaint)
        label?.let {
            val textWidth = labelPaint.measureText(it)
            val bx = cx + width * 0.18f
            val by = cy - width * 0.42f
            canvas.drawRoundRect(
                bx, by, bx + textWidth + 16f, by + 40f, 20f, 20f, labelBgPaint,
            )
            canvas.drawText(it, bx + 8f, by + 30f, labelPaint)
        }

        // 点击脉冲（扩散圆环）
        val since = SystemClock.uptimeMillis() - pulseStart
        if (since in 0..350) {
            val t = since / 350f
            pulsePaint.color = Color.argb(((1 - t) * 200).toInt(), 0, 137, 123)
            pulsePaint.strokeWidth = 3f * (1 - t) + 1f
            canvas.drawCircle(cx, cy, radius + t * width * 0.55f, pulsePaint)
            postInvalidateOnAnimation()
        }
    }
}
