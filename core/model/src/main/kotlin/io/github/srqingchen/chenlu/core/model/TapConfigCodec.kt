package io.github.srqingchen.chenlu.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * TapConfig 的 JSON 编解码（org.json 手写，免注解处理器与额外依赖）。
 * 用于任务库持久化与任务分享。
 */
object TapConfigCodec {

    fun toJson(config: TapConfig): String = JSONObject().apply {
        put("v", 5)
        put("intervalMs", config.intervalMs)
        put("pressDurationMs", config.pressDurationMs)
        put("order", config.order.name)
        put("totalClicks", config.totalClicks)
        put("totalDurationMs", config.totalDurationMs)
        put("jitterPx", config.jitterPx)
        put("jitterMs", config.jitterMs)
        put("jitterPressMs", config.jitterPressMs)
        put("swipeDx", config.swipeDx.toDouble())
        put("swipeDy", config.swipeDy.toDouble())
        put("swipeDurationMs", config.swipeDurationMs)
        if (config.strokes.isNotEmpty()) {
            put(
                "strokes",
                JSONArray().apply {
                    config.strokes.forEach { s ->
                        put(
                            JSONObject().put("pid", s.pointerId).put(
                                "pts",
                                JSONArray().apply {
                                    s.points.forEach { p ->
                                        put(JSONArray().put(p.t).put(p.x.toDouble()).put(p.y.toDouble()))
                                    }
                                },
                            ),
                        )
                    }
                },
            )
        }
        put(
            "vision",
            JSONObject().apply {
                val v = config.vision
                put("enabled", v.enabled)
                put("checkIntervalMs", v.checkIntervalMs)
                v.imageRule?.let { r ->
                    put(
                        "imageRule",
                        JSONObject()
                            .put("templateFile", r.templateFile)
                            .put("threshold", r.threshold.toDouble())
                            .put("dx", r.dx)
                            .put("dy", r.dy),
                    )
                }
                v.colorRule?.let { r ->
                    put(
                        "colorRule",
                        JSONObject()
                            .put("x", r.x)
                            .put("y", r.y)
                            .put("color", r.color)
                            .put("tolerance", r.tolerance)
                            .put("action", r.action.name),
                    )
                }
                v.textRule?.let { r ->
                    put("textRule", JSONObject().put("text", r.text))
                }
            },
        )
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
            jitterPx = o.optInt("jitterPx", 0),
            jitterMs = o.optLong("jitterMs", 0L),
            jitterPressMs = o.optLong("jitterPressMs", 0L),
            swipeDx = o.optDouble("swipeDx", 0.0).toFloat(),
            swipeDy = o.optDouble("swipeDy", 0.0).toFloat(),
            swipeDurationMs = o.optLong("swipeDurationMs", 0L),
            strokes = runCatching {
                val arr = o.optJSONArray("strokes") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val so = arr.getJSONObject(i)
                        val pts = so.optJSONArray("pts") ?: JSONArray()
                        add(
                            TouchStroke(
                                pointerId = so.optInt("pid", 0),
                                points = buildList {
                                    for (j in 0 until pts.length()) {
                                        val p = pts.getJSONArray(j)
                                        add(
                                            TimedPoint(
                                p.getLong(0),
                                p.getDouble(1).toFloat(),
                                p.getDouble(2).toFloat(),
                            ),
                                        )
                                    }
                                },
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList()),
            vision = runCatching {
                val vo = o.optJSONObject("vision") ?: return@runCatching VisionConfig()
                VisionConfig(
                    enabled = vo.optBoolean("enabled", false),
                    checkIntervalMs = vo.optLong("checkIntervalMs", 500L),
                    imageRule = vo.optJSONObject("imageRule")?.let { io ->
                        ImageRule(
                            templateFile = io.optString("templateFile"),
                            threshold = io.optDouble("threshold", 0.8).toFloat(),
                            dx = io.optInt("dx", 0),
                            dy = io.optInt("dy", 0),
                        )
                    },
                    colorRule = vo.optJSONObject("colorRule")?.let { co ->
                        ColorRule(
                            x = co.optInt("x", 0),
                            y = co.optInt("y", 0),
                            color = co.optInt("color", 0),
                            tolerance = co.optInt("tolerance", 40),
                            action = runCatching {
                                ColorAction.valueOf(co.optString("action", ColorAction.CLICK_POINT.name))
                            }.getOrDefault(ColorAction.CLICK_POINT),
                        )
                    },
                    textRule = vo.optJSONObject("textRule")?.let { to ->
                        TextRule(text = to.optString("text"))
                    },
                )
            }.getOrDefault(VisionConfig()),
        )
    }.getOrNull()
}
