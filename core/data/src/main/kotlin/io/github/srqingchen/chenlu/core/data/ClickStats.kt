package io.github.srqingchen.chenlu.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 点击统计：今日点击 / 累计点击 / 累计运行时长。
 * 内存累加、每 50 次与每次会话结束落盘（避免高频 IO）。
 */
object ClickStats {

    data class Stats(
        val totalClicks: Long = 0L,
        val todayClicks: Long = 0L,
        val todayStamp: String = "",
        val totalRunMs: Long = 0L,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var file: File? = null
    private var pendingSincePersist = 0L
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "stats.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            val today = today()
            val savedDay = o.optString("todayStamp", "")
            _stats.value = Stats(
                totalClicks = o.optLong("totalClicks", 0L),
                todayClicks = if (savedDay == today) o.optLong("todayClicks", 0L) else 0L,
                todayStamp = today,
                totalRunMs = o.optLong("totalRunMs", 0L),
            )
        }
    }

    @Synchronized
    fun onClick() {
        val today = today()
        val s = _stats.value
        _stats.value = if (s.todayStamp == today) {
            s.copy(totalClicks = s.totalClicks + 1, todayClicks = s.todayClicks + 1)
        } else {
            Stats(totalClicks = s.totalClicks + 1, todayClicks = 1, todayStamp = today, totalRunMs = s.totalRunMs)
        }
        if (++pendingSincePersist >= 50) persist()
    }

    @Synchronized
    fun onSession(durationMs: Long) {
        if (durationMs <= 0) return
        val s = _stats.value
        _stats.value = s.copy(totalRunMs = s.totalRunMs + durationMs)
        persist()
    }

    @Synchronized
    private fun persist() {
        val f = file ?: return
        val s = _stats.value
        pendingSincePersist = 0
        runCatching {
            f.writeText(
                JSONObject()
                    .put("totalClicks", s.totalClicks)
                    .put("todayClicks", s.todayClicks)
                    .put("todayStamp", s.todayStamp)
                    .put("totalRunMs", s.totalRunMs)
                    .toString(),
            )
        }
    }

    private fun today(): String = dayFmt.format(Date())
}
