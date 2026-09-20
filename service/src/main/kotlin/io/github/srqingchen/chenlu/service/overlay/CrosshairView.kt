package io.github.srqingchen.chenlu.service.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 十字准星：仅作为点击目标的可视化标记。
 * 所在窗口使用 FLAG_NOT_TOUCHABLE（触摸穿透），注入的点击事件不会命中本控件。
 */
class CrosshairView(context: Context) : View(context) {

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
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 0, 0, 0)
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
    }
}
