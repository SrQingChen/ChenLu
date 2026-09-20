package io.github.srqingchen.chenlu.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内环形日志：镜像写入 logcat，同时保留最近条目供“诊断”页展示与导出。
 * 用户反馈问题时导出此日志即可定位。
 */
object ChenLuLog {

    data class Entry(val millis: Long, val level: Char, val tag: String, val message: String)

    private const val MAX_ENTRIES = 400

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) = append('D', tag, message)
    fun i(tag: String, message: String) = append('I', tag, message)
    fun w(tag: String, message: String) = append('W', tag, message)
    fun e(tag: String, message: String) = append('E', tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    fun dump(): String = buildString {
        for (e in _entries.value) {
            append(fmt.format(Date(e.millis))).append(' ')
                .append(e.level).append('/').append(e.tag)
                .append(": ").append(e.message).append('\n')
        }
    }

    fun format(entry: Entry): String =
        "${fmt.format(Date(entry.millis))} ${entry.level}/${entry.tag}: ${entry.message}"

    private fun append(level: Char, tag: String, message: String) {
        val priority = when (level) {
            'E' -> Log.ERROR
            'W' -> Log.WARN
            'I' -> Log.INFO
            else -> Log.DEBUG
        }
        runCatching { Log.println(priority, "ChenLu/$tag", message) }
        _entries.update { list -> (list + Entry(System.currentTimeMillis(), level, tag, message)).takeLast(MAX_ENTRIES) }
    }
}
