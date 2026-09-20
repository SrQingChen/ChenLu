package io.github.srqingchen.chenlu.core.data

import android.content.Context
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.core.model.TapConfigCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 任务库：多点任务（含节奏/顺序/目标点）的保存、加载、删除、导出。
 * 持久化为 filesDir/tasks.json（org.json 手写编解码，Room 留待 M1 与 Hilt 一并迁移）。
 */
object TaskRepository {

    data class SavedTask(
        val id: String,
        val name: String,
        val createdAt: Long,
        val configJson: String,
    ) {
        val config: TapConfig? get() = TapConfigCodec.fromJson(configJson)
    }

    private val _tasks = MutableStateFlow<List<SavedTask>>(emptyList())
    val tasks: StateFlow<List<SavedTask>> = _tasks.asStateFlow()

    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "tasks.json")
        load()
        seedPresetsIfNeeded()
    }

    fun save(name: String, config: TapConfig): SavedTask {
        val task = SavedTask(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "任务 ${_tasks.value.size + 1}" },
            createdAt = System.currentTimeMillis(),
            configJson = TapConfigCodec.toJson(config),
        )
        _tasks.update { it + task }
        persist()
        return task
    }

    fun delete(taskId: String) {
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        persist()
    }

    private fun load() {
        val f = file ?: return
        val text = runCatching { f.readText() }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(text)
            val arr = root.optJSONArray("tasks") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        SavedTask(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "未命名"),
                            createdAt = o.optLong("createdAt", 0L),
                            configJson = o.optString("config", "{}"),
                        ),
                    )
                }
            }
            _tasks.value = list
        }
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            _tasks.value.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("name", t.name)
                        .put("createdAt", t.createdAt)
                        .put("config", JSONObject(t.configJson)),
                )
            }
            f.writeText(JSONObject().put("seeded", true).put("tasks", arr).toString())
        }
    }

    /** 首次启动写入预设模板（用户清空后不再回填）。 */
    private fun seedPresetsIfNeeded() {
        val f = file ?: return
        val seeded = runCatching { JSONObject(f.readText()).optBoolean("seeded", false) }
            .getOrDefault(false)
        if (seeded || _tasks.value.isNotEmpty()) {
            if (seeded) return
            persist()
            return
        }
        save("快速连点 · 50ms", TapConfig(intervalMs = 50, pressDurationMs = 40))
        save("稳定连点 · 100ms", TapConfig(intervalMs = 100))
        save("防检测 · 600ms 随机", TapConfig(intervalMs = 600, pressDurationMs = 90))
    }
}
