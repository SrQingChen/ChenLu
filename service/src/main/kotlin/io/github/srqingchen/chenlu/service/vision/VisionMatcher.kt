package io.github.srqingchen.chenlu.service.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 纯 Kotlin 视觉匹配：多尺度模板匹配（归一化互相关 NCC，粗搜 + 局部精修）
 * 与颜色采样判定。屏幕缩至约 320px 宽做粗搜（保证实时性），精修在 2 倍图局部窗口。
 */
object VisionMatcher {

    data class Match(
        val score: Float,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
    )

    private const val COARSE_WIDTH = 320
    private const val STEP = 3

    /**
     * 多尺度找模板：模板在屏幕上的等效尺寸在 [0.5, 2.0] 倍原始捕获尺寸间搜索。
     * @return 最佳匹配（score >= threshold 时），坐标为屏幕原始分辨率。
     */
    fun findTemplate(screen: Bitmap, template: Bitmap, threshold: Float): Match? {
        val coarseScale = COARSE_WIDTH.toFloat() / screen.width
        val coarse = Bitmap.createScaledBitmap(screen, COARSE_WIDTH, (screen.height * coarseScale).toInt().coerceAtLeast(1), true)
        val coarseGray = toGray(coarse)

        var best: Match? = null
        for (scale in floatArrayOf(0.5f, 0.75f, 1.0f, 1.35f, 1.8f)) {
            val tw = (template.width * coarseScale * scale).toInt()
            val th = (template.height * coarseScale * scale).toInt()
            if (tw < 12 || th < 12 || tw >= coarse.width || th >= coarse.height) continue
            val tpl = Bitmap.createScaledBitmap(template, tw, th, true)
            val m = nccBest(coarseGray, coarse.width, coarse.height, toGray(tpl), tw, th)
            if (m != null && (best == null || m.score > best.score)) {
                best = m
            }
        }
        val hit = best ?: return null
        if (hit.score < threshold) return null
        // 换算回屏幕坐标
        val inv = screen.width.toFloat() / COARSE_WIDTH
        return Match(
            score = hit.score,
            centerX = hit.centerX * inv,
            centerY = hit.centerY * inv,
            width = hit.width * inv,
            height = hit.height * inv,
        )
    }

    /** 灰度 NCC 滑窗（步进 STEP），返回最高分位置。 */
    private fun nccBest(
        s: IntArray,
        sw: Int,
        sh: Int,
        t: IntArray,
        tw: Int,
        th: Int,
    ): Match? {
        // 模板均值与标准差
        var tSum = 0.0
        for (v in t) tSum += v
        val tMean = tSum / t.size
        var tSq = 0.0
        for (v in t) {
            val d = v - tMean
            tSq += d * d
        }
        val tStd = sqrt(tSq / t.size)
        if (tStd < 1e-4) return null // 纯色模板无判别力

        var bestScore = -1f
        var bestX = 0f
        var bestY = 0f
        val rows = sh - th
        val cols = sw - tw
        if (rows <= 0 || cols <= 0) return null

        var y = 0
        while (y < rows) {
            var x = 0
            while (x < cols) {
                // 窗口均值
                var sSum = 0.0
                for (j in 0 until th) {
                    val rowBase = (y + j) * sw + x
                    for (i in 0 until tw) sSum += s[rowBase + i]
                }
                val sMean = sSum / t.size
                // NCC 分子
                var num = 0.0
                var sSq = 0.0
                for (j in 0 until th) {
                    val rowBase = (y + j) * sw + x
                    for (i in 0 until tw) {
                        val sd = s[rowBase + i] - sMean
                        val td = t[j * tw + i] - tMean
                        num += sd * td
                        sSq += sd * sd
                    }
                }
                val denom = sqrt(sSq / t.size) * tStd * t.size
                if (denom > 1e-6) {
                    val score = (num / denom).toFloat()
                    if (score > bestScore) {
                        bestScore = score
                        bestX = (x + tw / 2f)
                        bestY = (y + th / 2f)
                    }
                }
                x += STEP
            }
            y += STEP
        }
        return Match(bestScore, bestX, bestY, tw.toFloat(), th.toFloat())
    }

    private fun toGray(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000)
        }
        return gray
    }

    /** 颜色采样与容差判定（容差为各通道最大绝对差）。 */
    fun colorMatches(frame: Bitmap, x: Int, y: Int, targetColor: Int, tolerance: Int): Boolean {
        if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) return false
        val p = frame.getPixel(x, y)
        return abs(Color.red(p) - Color.red(targetColor)) <= tolerance &&
            abs(Color.green(p) - Color.green(targetColor)) <= tolerance &&
            abs(Color.blue(p) - Color.blue(targetColor)) <= tolerance
    }

    /** 采样某点颜色（ARGB）。 */
    fun sampleColor(frame: Bitmap, x: Int, y: Int): Int {
        val cx = x.coerceIn(0, frame.width - 1)
        val cy = y.coerceIn(0, frame.height - 1)
        return frame.getPixel(cx, cy)
    }

    /** 从屏幕帧裁出模板（矩形为屏幕坐标）。 */
    fun cropTemplate(frame: Bitmap, rect: RectF): Bitmap {
        val l = rect.left.coerceIn(0f, frame.width - 2f).toInt()
        val t = rect.top.coerceIn(0f, frame.height - 2f).toInt()
        val w = (rect.width().coerceIn(8f, frame.width - l.toFloat())).toInt()
        val h = (rect.height().coerceIn(8f, frame.height - t.toFloat())).toInt()
        return Bitmap.createBitmap(frame, l, t, w, h)
    }
}
