package io.github.srqingchen.chenlu.feature.home

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.core.designsystem.theme.GlassCard
import io.github.srqingchen.chenlu.core.model.ColorAction
import io.github.srqingchen.chenlu.core.model.ColorRule
import io.github.srqingchen.chenlu.core.model.ImageRule
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.core.model.TextRule
import io.github.srqingchen.chenlu.service.AutomationController
import io.github.srqingchen.chenlu.service.vision.ScreenCaptor
import io.github.srqingchen.chenlu.service.vision.VisionCaptureService
import io.github.srqingchen.chenlu.service.vision.VisionMatcher
import io.github.srqingchen.chenlu.service.vision.VisionStore
import kotlin.math.min

/** 视觉触发卡：授权屏幕捕获 + OCR/图片/颜色三类规则。 */
@Composable
fun VisionCard(config: TapConfig) {
    val context = LocalContext.current
    val vision = config.vision
    val ready by ScreenCaptor.ready.collectAsStateWithLifecycle()
    var pickFrame by remember { mutableStateOf<Bitmap?>(null) }
    var pickTemplateMode by remember { mutableStateOf(true) }
    var textInput by remember { mutableStateOf(vision.textRule?.text ?: "") }

    fun updateVision(transform: (io.github.srqingchen.chenlu.core.model.VisionConfig) ->
        io.github.srqingchen.chenlu.core.model.VisionConfig) {
        AutomationController.updateConfig { it.copy(vision = transform(it.vision)) }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            VisionCaptureService.start(context, result.resultCode, result.data!!)
        } else {
            ChenLuLog.w("vision", "屏幕捕获授权被拒绝")
        }
    }

    GlassCard {
        Text("视觉触发", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "授权屏幕捕获后：按检查间隔截屏 → 依次求值 OCR 文字 → 图片模板 → 颜色条件，" +
                "命中即执行动作。全部本地处理，不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用视觉触发", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = vision.enabled,
                onCheckedChange = { enabled ->
                    updateVision { it.copy(enabled = enabled) }
                },
            )
        }

        if (ready) {
            Text(
                "屏幕捕获就绪（${ScreenCaptor.screenWidth}x${ScreenCaptor.screenHeight}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(
                onClick = {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    manager?.let { projectionLauncher.launch(it.createScreenCaptureIntent()) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("授权屏幕捕获") }
            Text(
                "系统规定：每次重启后需重新授权一次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ready) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    pickTemplateMode = true
                    pickFrame = ScreenCaptor.capture()
                }) { Text("截图选模板") }
                FilledTonalButton(onClick = {
                    pickTemplateMode = false
                    pickFrame = ScreenCaptor.capture()
                }) { Text("截图取色") }
            }
        }

        // 图片模板规则
        vision.imageRule?.let { rule ->
            Text(
                "图片模板：已设置（阈值 ${"%.2f".format(rule.threshold)}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("置信度阈值：命中分数 ≥ 阈值才点击", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = rule.threshold,
                onValueChange = { v ->
                    updateVision { it.copy(imageRule = rule.copy(threshold = v)) }
                },
                valueRange = 0.5f..0.95f,
            )
            Text("点击偏移 X：${rule.dx}px", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = rule.dx.toFloat(),
                onValueChange = { v ->
                    updateVision { it.copy(imageRule = rule.copy(dx = v.toInt())) }
                },
                valueRange = -100f..100f,
            )
            Text("点击偏移 Y：${rule.dy}px", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = rule.dy.toFloat(),
                onValueChange = { v ->
                    updateVision { it.copy(imageRule = rule.copy(dy = v.toInt())) }
                },
                valueRange = -100f..100f,
            )
            Text(
                "图片匹配为多尺度（模板在屏幕上 0.5~1.8 倍大小均可命中），点击位置=图片中心+偏移。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                VisionStore.deleteTemplate(rule.templateFile)
                updateVision { it.copy(imageRule = null) }
            }) { Text("清除图片规则") }
        }

        // 颜色规则
        vision.colorRule?.let { rule ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(Color(rule.color)),
                )
                Text(
                    "  颜色 (${rule.x}, ${rule.y})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text("颜色容差：${rule.tolerance}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = rule.tolerance.toFloat(),
                onValueChange = { v ->
                    updateVision { it.copy(colorRule = rule.copy(tolerance = v.toInt())) }
                },
                valueRange = 0f..120f,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = rule.action == ColorAction.CLICK_POINT,
                    onClick = {
                        updateVision { it.copy(colorRule = rule.copy(action = ColorAction.CLICK_POINT)) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("命中→点击该点") }
                SegmentedButton(
                    selected = rule.action == ColorAction.STOP_TASK,
                    onClick = {
                        updateVision { it.copy(colorRule = rule.copy(action = ColorAction.STOP_TASK)) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("命中→停止任务") }
            }
            TextButton(onClick = { updateVision { it.copy(colorRule = null) } }) {
                Text("清除颜色规则")
            }
        }

        // OCR 文字规则
        Text("OCR 文字点击（中文识别，内置离线模型）", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("屏幕上出现的文字") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (textInput.isNotBlank()) {
                    updateVision { it.copy(textRule = TextRule(textInput.trim())) }
                }
            }) { Text("设置") }
        }
        vision.textRule?.let { rule ->
            Text(
                "文字规则：「${rule.text}」→ 点击其中心",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = { updateVision { it.copy(textRule = null) } }) {
                Text("清除文字规则")
            }
        }

        Text("检查间隔：${vision.checkIntervalMs}ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = vision.checkIntervalMs.toFloat(),
            onValueChange = { v ->
                updateVision { it.copy(checkIntervalMs = v.toLong().coerceAtLeast(200L)) }
            },
            valueRange = 200f..2000f,
        )
    }

    pickFrame?.let { frame ->
        ScreenPickDialog(
            frame = frame,
            templateMode = pickTemplateMode,
            onTemplate = { bmp ->
                val file = VisionStore.saveTemplate(bmp)
                updateVision {
                    it.copy(imageRule = ImageRule(templateFile = file, threshold = 0.8f))
                }
                ChenLuLog.i("vision", "图片模板已保存：$file")
            },
            onColor = { x, y, color ->
                updateVision { it.copy(colorRule = ColorRule(x = x, y = y, color = color)) }
                ChenLuLog.i("vision", "颜色规则已设置：($x,$y)=#${Integer.toHexString(color)}")
            },
            onDismiss = { pickFrame = null },
        )
    }
}

/** 全屏截图选择对话框：框选模板 / 点击取色。 */
@Composable
private fun ScreenPickDialog(
    frame: Bitmap,
    templateMode: Boolean,
    onTemplate: (Bitmap) -> Unit,
    onColor: (x: Int, y: Int, color: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var isTemplateMode by remember { mutableStateOf(templateMode) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var tapPos by remember { mutableStateOf<Offset?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // 显示坐标 ↔ 位图坐标换算（ContentScale.Fit 居中）
    fun toBitmap(off: Offset): Offset? {
        if (boxSize == IntSize.Zero) return null
        val scale = min(
            boxSize.width.toFloat() / frame.width,
            boxSize.height.toFloat() / frame.height,
        )
        val offX = (boxSize.width - frame.width * scale) / 2f
        val offY = (boxSize.height - frame.height * scale) / 2f
        val bx = (off.x - offX) / scale
        val by = (off.y - offY) / scale
        if (bx < 0 || by < 0 || bx >= frame.width || by >= frame.height) return null
        return Offset(bx, by)
    }
    fun toDisplay(b: Offset): Offset {
        val scale = min(
            boxSize.width.toFloat() / frame.width,
            boxSize.height.toFloat() / frame.height,
        )
        val offX = (boxSize.width - frame.width * scale) / 2f
        val offY = (boxSize.height - frame.height * scale) / 2f
        return Offset(b.x * scale + offX, b.y * scale + offY)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it },
            ) {
                androidx.compose.foundation.Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isTemplateMode) {
                            if (isTemplateMode) {
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        dragStart = toBitmap(pos)
                                        dragEnd = dragStart
                                        tapPos = null
                                    },
                                    onDrag = { change, _ ->
                                        dragEnd = toBitmap(change.position) ?: dragEnd
                                    },
                                )
                            } else {
                                detectTapGestures { pos ->
                                    toBitmap(pos)?.let {
                                        tapPos = it
                                        dragStart = null
                                        dragEnd = null
                                    }
                                }
                            }
                        },
                    contentScale = ContentScale.Fit,
                )
                Canvas(Modifier.fillMaxSize()) {
                    val s = dragStart
                    val e = dragEnd
                    if (isTemplateMode && s != null && e != null) {
                        val tl = toDisplay(Offset(min(s.x, e.x), min(s.y, e.y)))
                        val br = toDisplay(Offset(maxOf(s.x, e.x), maxOf(s.y, e.y)))
                        drawRect(
                            color = Color(0xFF00897B),
                            topLeft = tl,
                            size = Size(br.x - tl.x, br.y - tl.y),
                        )
                    }
                    tapPos?.let { p ->
                        val c = toDisplay(p)
                        drawCircle(Color(0xFFFF5252), radius = 14f, center = c)
                        drawCircle(Color.White, radius = 4f, center = c)
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp),
            ) {
                Text(
                    if (isTemplateMode) "拖动框选要匹配的图片区域" else "点击要监控颜色的位置",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { isTemplateMode = !isTemplateMode }) {
                        Text(if (isTemplateMode) "切换为取色" else "切换为框选", color = Color.White)
                    }
                    Button(
                        enabled = if (isTemplateMode) dragStart != null && dragEnd != null else tapPos != null,
                        onClick = {
                            if (isTemplateMode) {
                                val s = dragStart!!
                                val e = dragEnd!!
                                val rect = RectF(
                                    min(s.x, e.x),
                                    min(s.y, e.y),
                                    maxOf(s.x, e.x),
                                    maxOf(s.y, e.y),
                                )
                                if (rect.width() >= 8 && rect.height() >= 8) {
                                    onTemplate(VisionMatcher.cropTemplate(frame, rect))
                                    onDismiss()
                                }
                            } else {
                                val p = tapPos!!
                                onColor(p.x.toInt(), p.y.toInt(), VisionMatcher.sampleColor(frame, p.x.toInt(), p.y.toInt()))
                                onDismiss()
                            }
                        },
                    ) { Text("确认") }
                    TextButton(onClick = onDismiss) { Text("取消", color = Color.White) }
                }
            }
        }
    }
}
