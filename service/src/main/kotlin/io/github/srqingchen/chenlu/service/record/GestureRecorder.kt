package io.github.srqingchen.chenlu.service.record

import android.content.Context
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.core.data.TaskRepository
import io.github.srqingchen.chenlu.core.model.TimedPoint
import io.github.srqingchen.chenlu.core.model.TouchStroke
import io.github.srqingchen.chenlu.core.shizuku.RawEvent
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.service.AutomationController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 手势录制：Shizuku getevent 只读流 → 轨迹任务（自动入库并加载）。 */
object GestureRecorder {

    fun start(): Boolean = ShizukuManager.startRecording() == null

    fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
        val raw = ShizukuManager.stopRecording()
        val strokes = GestureParser.convert(raw)
        if (strokes.isEmpty()) {
            ChenLuLog.w("recorder", "未解析到有效轨迹（原始事件 ${raw.size} 个）")
            return
        }
        val name = "录制 " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val config = AutomationController.state.value.config.copy(strokes = strokes)
        TaskRepository.save(name, config)
        AutomationController.updateConfig { it.copy(strokes = strokes) }
        AutomationController.updateTaskName(name)
        val maxDur = strokes.maxOf { it.durationMs }
        val totalPts = strokes.sumOf { it.points.size.toLong() }
        ChenLuLog.i(
            "recorder",
            "录制完成并已加载：${strokes.size} 指 · ${maxDur}ms · $totalPts 点 → 任务[$name]",
        )
    }

    fun clear() {
        AutomationController.updateConfig { it.copy(strokes = emptyList()) }
        AutomationController.updateTaskName(null)
        ChenLuLog.i("recorder", "已清除录制轨迹（恢复连点模式）")
    }
}

/** 多点触控协议 B 解析：EV_ABS(SLOT/TRACKING_ID/X/Y) + SYN_REPORT → 轨迹段。 */
object GestureParser {

    fun convert(raw: List<RawEvent>): List<TouchStroke> {
        val t0 = raw.firstOrNull()?.t ?: return emptyList()
        val strokes = mutableListOf<TouchStroke>()
        class Slot {
            var trackingId = -1
            var x = -1f
            var y = -1f
            val pts = mutableListOf<TimedPoint>()
        }
        val slots = HashMap<Int, Slot>()
        var currentSlot = 0

        for (e in raw) {
            when (e.type) {
                3 -> when (e.code) {
                    0x2f -> currentSlot = e.value // ABS_MT_SLOT
                    0x39 -> { // ABS_MT_TRACKING_ID
                        val s = slots.getOrPut(currentSlot) { Slot() }
                        if (e.value >= 0) {
                            s.trackingId = e.value
                            s.pts.clear()
                        } else if (s.trackingId >= 0) {
                            if (s.pts.size >= 2) {
                                strokes += TouchStroke(s.trackingId, s.pts.toList())
                            }
                            s.trackingId = -1
                        }
                    }
                    0x35 -> slots.getOrPut(currentSlot) { Slot() }.x = e.value.toFloat()
                    0x36 -> slots.getOrPut(currentSlot) { Slot() }.y = e.value.toFloat()
                }
                0 -> if (e.code == 0) { // SYN_REPORT：提交当前 slot 的点
                    val s = slots[currentSlot]
                    if (s != null && s.trackingId >= 0 && s.x >= 0 && s.y >= 0) {
                        s.pts += TimedPoint(e.t - t0, s.x, s.y)
                    }
                }
            }
        }
        return strokes
    }
}
