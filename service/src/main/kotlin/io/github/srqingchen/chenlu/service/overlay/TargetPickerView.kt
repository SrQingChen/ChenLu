package io.github.srqingchen.chenlu.service.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.srqingchen.chenlu.core.model.Point
import kotlin.math.abs

/**
 * 全屏目标选点器：点击屏幕添加目标点（支持多点），底部工具条：撤销 / 清空 / 完成。
 * 自身可触摸（选点期间拦截全部交互），坐标换算为屏幕绝对坐标系。
 */
class TargetPickerView(context: Context) : View(context) {

    var onComplete: ((List<Point>) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val picked = mutableListOf<Point>()
    private var screenOffsetX = 0
    private var screenOffsetY = 0

    private val scrimPaint = Paint().apply { color = Color.argb(48, 0, 0, 0) }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 137, 123) // ChenLuAccent
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 105, 92)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val toolbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 22, 28, 26)
    }
    private val toolbarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var toolbarRect = RectF()
    private val buttonRects = arrayOf(RectF(), RectF(), RectF())
    private val buttonLabels = arrayOf("撤销", "清空", "完成")

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val toolbarHeight = 56 * density
        val bottomInset = 24 * density
        toolbarRect = RectF(16 * density, h - toolbarHeight - bottomInset, w - 16 * density, h - bottomInset)
        val third = toolbarRect.width() / 3f
        for (i in buttonRects.indices) {
            buttonRects[i] = RectF(
                toolbarRect.left + third * i + 4 * density,
                toolbarRect.top,
                toolbarRect.left + third * (i + 1) - 4 * density,
                toolbarRect.bottom,
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            screenOffsetX = loc[0]
            screenOffsetY = loc[1]
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val markerRadius = 30f * resources.displayMetrics.density
        picked.forEachIndexed { index, p ->
            val vx = p.x - screenOffsetX
            val vy = p.y - screenOffsetY
            canvas.drawCircle(vx, vy, markerRadius, markerPaint)
            val reach = markerRadius
            val gap = markerRadius * 0.25f
            canvas.drawLine(vx - reach, vy, vx - gap, vy, crossPaint)
            canvas.drawLine(vx + gap, vy, vx + reach, vy, crossPaint)
            canvas.drawLine(vx, vy - reach, vx, vy - gap, crossPaint)
            canvas.drawLine(vx, vy + gap, vx, vy + reach, crossPaint)
            // 序号角标
            canvas.drawCircle(vx + markerRadius * 0.8f, vy - markerRadius * 0.8f, 26f, badgePaint)
            canvas.drawText("${index + 1}", vx + markerRadius * 0.8f, vy - markerRadius * 0.8f + 12f, badgeTextPaint)
        }

        // 顶部提示
        val hint = "点击屏幕添加目标点（已选 ${picked.size} 个）"
        val hintWidth = hintPaint.measureText(hint) + 48f
        val hintTop = 80f * resources.displayMetrics.density
        canvas.drawRoundRect(
            width / 2f - hintWidth / 2f, hintTop, width / 2f + hintWidth / 2f, hintTop + 72f,
            36f, 36f, hintBgPaint,
        )
        canvas.drawText(hint, width / 2f, hintTop + 50f, hintPaint)

        // 底部工具条
        canvas.drawRoundRect(toolbarRect, 28f, 28f, toolbarPaint)
        buttonRects.forEachIndexed { i, rect ->
            canvas.drawText(
                buttonLabels[i],
                rect.centerX(),
                rect.centerY() + 15f,
                toolbarTextPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) > slop * 2 || abs(event.y - downY) > slop * 2) return true
                // 工具条按钮
                for (i in buttonRects.indices) {
                    if (buttonRects[i].contains(event.x, event.y)) {
                        when (i) {
                            0 -> if (picked.isNotEmpty()) {
                                picked.removeAt(picked.size - 1)
                                invalidate()
                            }
                            1 -> if (picked.isNotEmpty()) {
                                picked.clear()
                                invalidate()
                            }
                            2 -> onComplete?.invoke(picked.toList())
                        }
                        return true
                    }
                }
                // 点击空白处添加目标点（避开工具条区域）
                if (!toolbarRect.contains(event.x, event.y)) {
                    picked.add(Point(event.x + screenOffsetX, event.y + screenOffsetY))
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 系统返回等场景外部取消。 */
    fun cancel() {
        onCancel?.invoke()
    }
}
