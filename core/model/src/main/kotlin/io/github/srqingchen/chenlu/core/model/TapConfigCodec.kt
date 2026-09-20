package io.github.srqingchen.chenlu.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * TapConfig 的 JSON 编解码（org.json 手写，免注解处理器与额外依赖）。
 * 用于任务库持久化与任务分享。
 */
object TapConfigCodec {

    fun toJson(config: TapConfig): String = JSONObject().apply {
        put("v", 2)
        put("intervalMs", config.intervalMs)
        put("pressDurationMs", config.pressDurationMs)
        put("order", config.order.name)
        put("totalClicks", config.totalClicks)
        put("totalDurationMs", config.totalDurationMs)
        put("targets", JSONArray().apply {
            config.targets.forEach { p ->
                put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
            }
        })
    }.toString()

    fun fromJson(json: String): TapConfig? = runCatching {
        val o = JSONObject(json)
        val targets = buildList {
            val arr = o.optJSONArray("targets") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                add(Point(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
            }
        }
        TapConfig(
            targets = targets,
            intervalMs = o.optLong("intervalMs", TapConfig.DEFAULT_INTERVAL_MS),
            pressDurationMs = o.optLong("pressDurationMs", TapConfig.DEFAULT_PRESS_MS),
            order = runCatching {
                TargetOrder.valueOf(o.optString("order", TargetOrder.SEQUENTIAL.name))
            }.getOrDefault(TargetOrder.SEQUENTIAL),
            totalClicks = o.optLong("totalClicks", 0L),
            totalDurationMs = o.optLong("totalDurationMs", 0L),
        )
    }.getOrNull()
}
