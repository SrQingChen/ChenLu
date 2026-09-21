package io.github.srqingchen.chenlu.service.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.github.srqingchen.chenlu.core.model.Point
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

/** OCR 文字查找（ML Kit 中文识别，模型内置离线、无需谷歌服务）。 */
object OcrMatcher {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 在帧中查找包含指定文本的行，返回其中心点。 */
    suspend fun find(frame: Bitmap, text: String): Point? {
        val target = text.trim()
        if (target.isEmpty()) return null
        return runCatching {
            val result = recognizer.process(InputImage.fromBitmap(frame, 0)).await()
            result.textBlocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .firstOrNull { it.text.contains(target, ignoreCase = true) }
                ?.boundingBox
                ?.let { box -> Point(box.exactCenterX(), box.exactCenterY()) }
        }.getOrNull()
    }
}

/** 模板图片文件管理（filesDir/templates/）。 */
object VisionStore {

    private const val DIR = "templates"

    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
    }

    fun saveTemplate(bitmap: Bitmap): String {
        val name = "tpl_${UUID.randomUUID().toString().take(8)}.png"
        val file = File(dir ?: return name, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    private val cache = HashMap<String, Bitmap>()

    fun loadTemplate(name: String): Bitmap? {
        if (name.isBlank()) return null
        cache[name]?.let { return it }
        val file = File(dir ?: return null, name)
        if (!file.exists()) return null
        return runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()?.also { cache[name] = it }
    }

    fun deleteTemplate(name: String) {
        cache.remove(name)
        runCatching { File(dir, name).delete() }
    }

    /** 视觉规则求值（顺序：OCR → 图片 → 颜色），返回本帧动作。 */
    data class VisionAction(val click: Point?, val stopTask: Boolean)

    suspend fun evaluate(
        frame: Bitmap,
        vision: io.github.srqingchen.chenlu.core.model.VisionConfig,
    ): VisionAction {
        val v = vision
        // 1) OCR 文字
        v.textRule?.let { rule ->
            OcrMatcher.find(frame, rule.text)?.let { pt ->
                return VisionAction(pt, false)
            }
        }
        // 2) 图片模板
        v.imageRule?.let { rule ->
            val template = loadTemplate(rule.templateFile) ?: return@let
            val match = VisionMatcher.findTemplate(frame, template, rule.threshold)
            if (match != null) {
                return VisionAction(
                    Point(match.centerX + rule.dx, match.centerY + rule.dy),
                    false,
                )
            }
        }
        // 3) 颜色条件
        v.colorRule?.let { rule ->
            val matched = VisionMatcher.colorMatches(
                frame, rule.x, rule.y, rule.color, rule.tolerance,
            )
            if (matched) {
                return when (rule.action) {
                    io.github.srqingchen.chenlu.core.model.ColorAction.CLICK_POINT ->
                        VisionAction(Point(rule.x.toFloat(), rule.y.toFloat()), false)
                    io.github.srqingchen.chenlu.core.model.ColorAction.STOP_TASK ->
                        VisionAction(null, true)
                }
            }
        }
        return VisionAction(null, false)
    }

    /** 截屏取帧并裁模板（截图-选区流程用）。 */
    fun captureAndCrop(rect: RectF): Bitmap? {
        val frame = ScreenCaptor.capture() ?: return null
        return VisionMatcher.cropTemplate(frame, rect)
    }
}
