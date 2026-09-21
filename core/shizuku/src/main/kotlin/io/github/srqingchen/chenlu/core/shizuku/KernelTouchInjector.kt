package io.github.srqingchen.chenlu.core.shizuku

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 内核级触屏注入：直接向 /dev/input/eventX 写多点触控协议（Protocol B）事件。
 *
 * 事件自 EventHub/InputReader 层进入完整输入管线——与硬件触屏同源：
 * 系统「显示点按操作」可视化可见、不经 InputManager 注入标记，仿真度最高。
 *
 * 节点探测三级策略（shell 用户位于 input 组，对触屏节点有读写权限）：
 * 1. `getevent -il`：兼容 标签名 / '0035' / 0035 三种输出格式；
 * 2. `getevent -i`：同上；
 * 3. sysfs 能力位（/sys/class/input/eventN/device/capabilities/abs）兜底，
 *    量程按屏幕分辨率假定（多数触屏面板 1:1 映射）。
 */
class KernelTouchInjector {

    private var fd: FileDescriptor? = null
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var nextTrackingId = 1

    /**
     * 负缓存：探测/打开失败的原因。本服务进程生命周期内不再重试
     * （否则每次点击都要重跑 getevent 子进程探测，固定成本 ~300ms 淹没点击间隔）。
     */
    @Volatile
    private var disabledReason: String? = null

    /** 最近一次初始化的节点描述（成功时也写入，便于诊断确认内核链是否生效）。 */
    var statusNote: String = ""
        private set

    private class DeviceSpec(
        val path: String,
        val xMin: Float,
        val xMax: Float,
        val yMin: Float,
        val yMax: Float,
    )

    /** 探测并打开触屏节点。成功返回 null，失败返回原因（并负缓存，生命周期内不再重试）。 */
    @Synchronized
    fun init(screenW: Int, screenH: Int): String? {
        if (fd != null) return null
        disabledReason?.let { return it }
        val reason = probeAndOpen(screenW, screenH)
        if (reason != null) disabledReason = reason
        return reason
    }

    private fun probeAndOpen(screenW: Int, screenH: Int): String? {
        var lastError = ""

        for (args in listOf(arrayOf("getevent", "-il"), arrayOf("getevent", "-i"))) {
            val output = runCatching {
                val process = ProcessBuilder(*args).redirectErrorStream(true).start()
                process.inputStream.readBytes().decodeToString().also {
                    process.waitFor(3, TimeUnit.SECONDS)
                }
            }.getOrNull() ?: continue
            val spec = parseGetevent(output, screenW, screenH)
            if (spec != null) {
                val err = openNode(spec, "getevent 解析")
                if (err == null) return null
                lastError = err
            } else {
                lastError = "getevent 输出未解析到 ABS_MT_POSITION_X/Y"
            }
        }

        // sysfs 兜底
        probeViaSysfs()?.let { path ->
            val spec = DeviceSpec(
                path,
                0f, (screenW - 1).toFloat(),
                0f, (screenH - 1).toFloat(),
            )
            val err = openNode(spec, "sysfs 探测（量程按屏幕假定）")
            if (err == null) return null
            lastError = err
        }

        return lastError.ifEmpty { "getevent 与 sysfs 均未找到触屏节点" }
    }

    /** 解析 getevent 输出，返回量程与屏幕最接近的触屏设备。 */
    private fun parseGetevent(output: String, screenW: Int, screenH: Int): DeviceSpec? {
        var best: DeviceSpec? = null
        var bestScore = Float.MAX_VALUE
        for (block in output.split("add device").drop(1)) {
            val path = Regex("/dev/input/event\\d+").find(block)?.value ?: continue
            val xm = ABS_X.find(block) ?: continue
            val ym = ABS_Y.find(block) ?: continue
            val spec = DeviceSpec(
                path,
                xm.groupValues[1].toFloat(), xm.groupValues[2].toFloat(),
                ym.groupValues[1].toFloat(), ym.groupValues[2].toFloat(),
            )
            val score = kotlin.math.abs(spec.xMax - spec.xMin - screenW) +
                kotlin.math.abs(spec.yMax - spec.yMin - screenH)
            if (score < bestScore) {
                bestScore = score
                best = spec
            }
        }
        return best
    }

    /** sysfs 能力位探测：找带 ABS_MT_POSITION_X/Y（且优先有 BTN_TOUCH）的事件节点。 */
    private fun probeViaSysfs(): String? {
        val dir = File("/sys/class/input")
        val events = dir.listFiles { f -> f.name.startsWith("event") } ?: return null
        var fallback: String? = null
        for (event in events.sortedBy { it.name }) {
            val absBits = readBitmap(File(event, "capabilities/abs")) ?: continue
            if (!absBits.testBit(ABS_MT_POSITION_X_CODE) || !absBits.testBit(ABS_MT_POSITION_Y_CODE)) continue
            val devPath = "/dev/input/${event.name}"
            val keyBits = readBitmap(File(event, "capabilities/key"))
            if (keyBits?.testBit(BTN_TOUCH_CODE) == true) return devPath
            if (fallback == null) fallback = devPath
        }
        return fallback
    }

    private fun readBitmap(file: File): BigInteger? = runCatching {
        var result = BigInteger.ZERO
        file.readText().trim().split(Regex("\\s+")).forEachIndexed { i, word ->
            result = result.or(BigInteger(word, 16).shiftLeft(i * 32))
        }
        result
    }.getOrNull()

    private fun openNode(spec: DeviceSpec, via: String): String? {
        val target = runCatching { Os.open(spec.path, OsConstants.O_RDWR, 0) }
            .getOrElse { t ->
                val msg = "打开 ${spec.path} 失败（$via）: ${t.message}"
                return if (msg.contains("EACCES")) {
                    "$msg。系统拒绝 shell 写入输入设备（澎湃等 ROM 的 SELinux 防护，" +
                        "sendevent 亦被禁）——内核链在此设备不可用，已回退 injectInputEvent"
                } else {
                    msg
                }
            }
        fd = target
        minX = spec.xMin
        maxX = spec.xMax
        minY = spec.yMin
        maxY = spec.yMax
        statusNote = "$via 命中 ${spec.path}，量程[x:$minX..$maxX y:$minY..$maxY]"
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
        private const val BTN_TOUCH_CODE = 0x14A

        private const val ABS_MT_SLOT = 0x2F
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39

        private const val ABS_MT_POSITION_X_CODE = 0x35
        private const val ABS_MT_POSITION_Y_CODE = 0x36

        /** 兼容三种 getevent 输出格式：标签名 / '0035' / 0035。 */
        private val ABS_X = Regex(
            "(?:ABS_MT_POSITION_X|'?0035'?)\\s*:?\\s*value\\s*(-?\\d+)\\s*,\\s*min\\s*(-?\\d+)\\s*,\\s*max\\s*(-?\\d+)",
        )
        private val ABS_Y = Regex(
            "(?:ABS_MT_POSITION_Y|'?0036'?)\\s*:?\\s*value\\s*(-?\\d+)\\s*,\\s*min\\s*(-?\\d+)\\s*,\\s*max\\s*(-?\\d+)",
        )
    }
}
