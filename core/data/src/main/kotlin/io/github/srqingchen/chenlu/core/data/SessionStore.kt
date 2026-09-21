package io.github.srqingchen.chenlu.core.data

import android.content.Context
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.core.model.TapConfigCodec
import org.json.JSONObject
import java.io.File

/**
 * 会话存储：退出前最后使用的连点配置 + 引擎偏好 + 岛开关。
 * 启动时恢复，变更时防抖落盘。
 */
object SessionStore {

    private const val FILE = "session.json"

    fun load(context: Context): JSONObject? = runCatching {
        JSONObject(File(context.applicationContext.filesDir, FILE).readText())
    }.getOrNull()

    fun loadConfig(context: Context): TapConfig? {
        val root = load(context) ?: return null
        val configJson = root.optJSONObject("config")?.toString() ?: return null
        return TapConfigCodec.fromJson(configJson)
    }

    fun loadEnginePreference(context: Context): String? {
        val root = load(context) ?: return null
        val pref = root.optString("enginePreference", "")
        return pref.ifBlank { null }
    }

    fun loadIslandFlags(context: Context): IslandFlags? {
        val root = load(context) ?: return null
        if (!root.has("islandEnabled")) return null
        return IslandFlags(
            enabled = root.optBoolean("islandEnabled", true),
            compat = root.optBoolean("islandCompat", true),
            idle = root.optBoolean("islandIdle", true),
            flow = root.optBoolean("islandFlow", true),
        )
    }

    data class IslandFlags(val enabled: Boolean, val compat: Boolean, val idle: Boolean, val flow: Boolean)

    /** 保存当前连点配置（合并写入，其他键保留）。 */
    fun saveConfig(context: Context, config: TapConfig) {
        val root = load(context) ?: JSONObject()
        root.put("config", JSONObject(TapConfigCodec.toJson(config)))
        save(context, root)
    }

    fun saveEnginePreference(context: Context, preference: String?) {
        val root = load(context) ?: JSONObject()
        root.put("enginePreference", preference ?: "")
        save(context, root)
    }

    fun saveIslandFlags(context: Context, flags: IslandFlags) {
        val root = load(context) ?: JSONObject()
        root.put("islandEnabled", flags.enabled)
        root.put("islandCompat", flags.compat)
        root.put("islandIdle", flags.idle)
        root.put("islandFlow", flags.flow)
        save(context, root)
    }

    private fun save(context: Context, root: JSONObject) {
        runCatching {
            File(context.applicationContext.filesDir, FILE).writeText(root.toString())
        }
    }
}
