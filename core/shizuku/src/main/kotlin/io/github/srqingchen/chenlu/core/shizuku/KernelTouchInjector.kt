package io.github.srqingchen.chenlu.core.shizuku

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 内核级触屏注入：直接向 /dev/input/eventX 写多点触控协议（Protocol B）事件。
 *
 * 事件自 EventHub/InputReader 层进入完整输入管线——与硬件触屏同源：
 * 系统「显示点按操作」可视化可见、不经 InputManager 注入标记，仿真度最高。
 *
 * shell 用户位于 input 组，对触屏节点有读写权限。节点与量程经 `getevent -i`
 * 文本解析获得（免 ioctl）。任一步失败即报错，由调用方回退 injectInputEvent。
 */
class KernelTouchInjector {

    private var fd: FileDescriptor? = null
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var nextTrackingId = 1

    /** 最近一次成功初始化的节点描述（写入诊断）。 */
    var statusNote: String = ""
        private set

    private class Axis(val min: Float, val max: Float) {
        val range: Float get() = max - min
    }

    /** 探测触屏节点。成功返回 null，失败返回原因。 */
    @Synchronized
    fun init(screenW: Int, screenH: Int): String? {
        if (fd != null) return null
        val output = runCatching {
            val process = ProcessBuilder("getevent", "-i").redirectErrorStream(true).start()
            process.inputStream.readBytes().decodeToString().also {
                process.waitFor(3, TimeUnit.SECONDS)
            }
        }.getOrNull() ?: return "getevent -i 执行失败"

        var bestPath: String? = null
        var bestX: Axis? = null
        var bestY: Axis? = null
        var bestScore = Float.MAX_VALUE

        for (block in output.split("add device").drop(1)) {
            val path = Regex("/dev/input/event\\d+").find(block)?.value ?: continue
            val xm = Regex("'0035':\\s*value\\s*[-\\d]+,\\s*min\\s*(-?[\\d.]+),\\s*max\\s*(-?[\\d.]+)")
                .find(block) ?: continue
            val ym = Regex("'0036':\\s*value\\s*[-\\d]+,\\s*min\\s*(-?[\\d.]+),\\s*max\\s*(-?[\\d.]+)")
                .find(block) ?: continue
            val x = Axis(xm.groupValues[1].toFloat(), xm.groupValues[2].toFloat())
            val y = Axis(ym.groupValues[1].toFloat(), ym.groupValues[2].toFloat())
            // 优先选择量程与屏幕尺寸最接近的设备（排除触控板/副屏）
            val score = kotlin.math.abs(x.range - screenW) + kotlin.math.abs(y.range - screenH)
            if (score < bestScore) {
                bestScore = score
                bestPath = path
                bestX = x
                bestY = y
            }
        }
        val path = bestPath ?: return "未解析到带 ABS_MT_POSITION_X/Y 的触屏节点"
        val target = runCatching { Os.open(path, OsConstants.O_RDWR, 0) }
            .getOrElse { return "打开 $path 失败: ${it.message}" }
        fd = target
        minX = bestX!!.min
        maxX = bestX!!.max
        minY = bestY!!.min
        maxY = bestY!!.max
        statusNote = "$path 量程[x:$minX..$maxX y:$minY..$maxY]"
        return null
    }

    /** 注入一次完整点击（Protocol B：TRACKING_ID + 坐标 + BTN_TOUCH + SYN）。失败返回原因。 */
    @Synchronized
    fun tap(x: Float, y: Float, screenW: Int, screenH: Int, durationMs: Long): String? {
        init(screenW, screenH)?.let { return it }
        val target = fd ?: return "触屏节点未就绪"
        val id = nextTrackingId++
        val dx = mapAxis(x, screenW, minX, maxX)
        val dy = mapAxis(y, screenH, minY, maxY)

        val down = buildByteArray {
            putEvent(EV_ABS, ABS_MT_SLOT, 0)
            putEvent(EV_ABS, ABS_MT_TRACKING_ID, id)
            putEvent(EV_ABS, ABS_MT_POSITION_X, dx)
            putEvent(EV_ABS, ABS_MT_POSITION_Y, dy)
            putEvent(EV_KEY, BTN_TOUCH, 1)
            putEvent(EV_SYN, SYN_REPORT, 0)
        }
        try {
            Os.write(target, down, 0, down.size)
        } catch (t: Throwable) {
            return "内核写入(DOWN)失败: ${t.message}"
        }

        if (durationMs >= 16) {
            runCatching { Thread.sleep(durationMs) }
        }

        val up = buildByteArray {
            putEvent(EV_ABS, ABS_MT_TRACKING_ID, -1)
            putEvent(EV_KEY, BTN_TOUCH, 0)
            putEvent(EV_SYN, SYN_REPORT, 0)
        }
        try {
            Os.write(target, up, 0, up.size)
        } catch (t: Throwable) {
            return "内核写入(UP)失败: ${t.message}"
        }
        return null
    }

    private fun mapAxis(value: Float, screen: Int, min: Float, max: Float): Int {
        if (screen <= 1) return value.toInt().coerceIn(min.toInt(), max.toInt())
        val mapped = min + (value / (screen - 1)) * (max - min)
        return mapped.toInt().coerceIn(min.toInt(), max.toInt())
    }

    /** struct input_event（64 位用户态 24 字节）：timeval(16) + type(2) + code(2) + value(4)。 */
    private fun ByteArrayBuilder.putEvent(type: Int, code: Int, value: Int) {
        buffer.putLong(0L) // sec（内核写入时会重新打时间戳）
        buffer.putLong(0L) // usec
        buffer.putShort(type.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(value)
    }

    private class ByteArrayBuilder(val size: Int = 6 * EVENT_SIZE) {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fun build(): ByteArray = buffer.array().copyOfRange(0, buffer.position())
    }

    private fun buildByteArray(block: ByteArrayBuilder.() -> Unit): ByteArray =
        ByteArrayBuilder().apply(block).build()

    companion object {
        private const val EVENT_SIZE = 24

        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03

        private const val SYN_REPORT = 0x00
        private const val BTN_TOUCH = 0x14A

        private const val ABS_MT_SLOT = 0x2F
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
    }
}
