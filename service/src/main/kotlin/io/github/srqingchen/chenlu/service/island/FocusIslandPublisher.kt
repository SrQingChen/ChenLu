package io.github.srqingchen.chenlu.service.island

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.service.AutomationService
import io.github.srqingchen.chenlu.service.R
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 原生超级岛发布器（MAA-Meow 同款路线）：
 * 原生 Notification extras 塞 `miui.focus.param`（V3 模板），澎湃 OS 3 以岛样式渲染。
 *
 * 「兼容模式」：非白名单应用直发会被云端鉴权摘岛（-200）；发布期间经 Shizuku
 * 临时切断 com.xiaomi.xmsf 联网，令鉴权 fail-open（-400 视为放行）。
 * 副作用：期间全机小米推送延迟，任务结束自动恢复（状态落盘防遗留）。
 */
object FocusIslandPublisher {

    private const val CHANNEL_ID = "chenlu_island"
    private const val NOTIF_ID = 2001
    private const val ACCENT = "#00897B"
    private const val ACCENT_UNREACH = "#33FFFFFF"

    /** 岛显示总开关（UI 可切换）。 */
    @Volatile
    var enabled = true

    /** 兼容模式（断 xmsf 令鉴权放行）默认开启；需 Shizuku 就绪。 */
    @Volatile
    var compatMode = true

    private val gateExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var gateActive = false

    @Volatile
    private var channelReady = false

    fun publish(
        context: Context,
        running: Boolean,
        count: Long,
        config: TapConfig,
        engineId: String?,
    ) {
        if (!enabled) return
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (compatMode && running && ShizukuManager.injector != null) {
            engageGate(appContext)
        }
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(appContext, running, count, config, engineId))
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        releaseGate(appContext)
    }

    /** 服务启动时调用：若上次异常遗留了断网状态，恢复 xmsf。 */
    fun restoreGateIfNeeded(context: Context) {
        val flag = gateFlagFile(context)
        if (flag.exists()) {
            gateExecutor.execute {
                val err = ShizukuManager.xmsfGate(false)
                if (err == null) {
                    flag.delete()
                    gateActive = false
                    ChenLuLog.i("island", "已恢复上次遗留的 xmsf 断网状态")
                }
            }
        }
    }

    private fun engageGate(context: Context) {
        if (gateActive) return
        gateExecutor.execute {
            val err = ShizukuManager.xmsfGate(true)
            if (err == null) {
                gateActive = true
                runCatching { gateFlagFile(context).writeText("blocked") }
                ChenLuLog.i("island", "兼容模式：已临时切断 xmsf 联网（岛鉴权 fail-open）")
            } else {
                ChenLuLog.w("island", "兼容模式切断 xmsf 失败（岛可能不上岛，退化为通知）: $err")
            }
        }
    }

    private fun releaseGate(context: Context) {
        if (!gateActive) return
        gateExecutor.execute {
            // 延迟恢复，确保岛已渲染完成
            runCatching { Thread.sleep(1500) }
            val err = ShizukuManager.xmsfGate(false)
            if (err == null) {
                gateActive = false
                runCatching { gateFlagFile(context).delete() }
                ChenLuLog.i("island", "兼容模式：xmsf 联网已恢复")
            } else {
                ChenLuLog.w("island", "xmsf 恢复失败（将在下次启动时重试）: $err")
            }
        }
    }

    private fun gateFlagFile(context: Context): File =
        File(context.applicationContext.filesDir, "island_gate.flag")

    private fun ensureChannel(context: Context) {
        if (channelReady) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "超级岛状态", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "尘露任务状态上岛（澎湃 OS 3）"
                setSound(null, null)
                enableVibration(false)
            },
        )
        channelReady = true
    }

    private fun buildNotification(
        context: Context,
        running: Boolean,
        count: Long,
        config: TapConfig,
        engineId: String?,
    ): android.app.Notification {
        val title = if (running) "尘露 · 运行中" else "尘露 · 已停止"
        val content = buildString {
            append(count).append(" 次 · ").append(config.intervalMs).append("ms")
            if (config.targets.size > 1) {
                append(" · ").append(config.targets.size).append("点")
                append(if (config.order == io.github.srqingchen.chenlu.core.model.TargetOrder.RANDOM) "随机" else "顺序")
            }
            engineId?.let { append(" · ").append(it) }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chenlu)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(android.app.Notification.CATEGORY_PROGRESS)
            .addAction(0, if (running) "暂停" else "开始", actionIntent(context, AutomationService.ACTION_TOGGLE))
            .addAction(0, "停止", actionIntent(context, AutomationService.ACTION_STOP))
            .setContentIntent(launchIntent(context))

        val icon = simpleIcon(context)
        val extras = Bundle().apply {
            putString("miui.focus.param", buildFocusJson(NOTIF_ID.toString(), title, content, count))
            putBundle(
                "miui.focus.pics",
                Bundle().apply {
                    putParcelable("miui.focus.pic_progress_app", icon)
                    putParcelable("miui.focus.pic_progress_capsule", icon)
                },
            )
        }
        builder.addExtras(extras)
        return builder.build()
    }

    /** V3 焦点岛参数（business 复用官方 download_progress 模板，进度环随计数循环）。 */
    private fun buildFocusJson(notifyId: String, title: String, content: String, count: Long): String {
        val progress = (count % 100).toInt().coerceIn(0, 100)
        val root = JSONObject().put(
            "param_v2",
            JSONObject()
                .put("business", "download_progress")
                .put("updatable", true)
                .put("notifyId", notifyId)
                .put("timeout", 1440)
                .put("sequence", SystemClock.elapsedRealtime())
                .put("aodTitle", title)
                .put("ticker", "$title $content")
                .put("reopen", "reopen")
                .put("islandProperty", 1)
                .put("islandTimeout", 86400)
                .put("dismissIsland", false)
                .put(
                    "chatInfo",
                    JSONObject().put("title", title).put("content", content),
                )
                .put("multiProgressInfo", JSONObject().put("progress", progress).put("color", ACCENT))
                .put(
                    "bigIslandArea",
                    JSONObject().put(
                        "imageTextInfoLeft",
                        JSONObject()
                            .put("type", 1)
                            .put(
                                "picInfo",
                                JSONObject().put("type", 1).put("pic", "miui.focus.pic_progress_app"),
                            )
                            .put(
                                "textInfo",
                                JSONObject()
                                    .put("title", title)
                                    .put("content", content)
                                    .put("showHighlightColor", true),
                            ),
                    ),
                )
                .put(
                    "smallIslandArea",
                    JSONObject().put(
                        "combinePicInfo",
                        JSONObject()
                            .put(
                                "picInfo",
                                JSONObject().put("type", 1).put("pic", "miui.focus.pic_progress_capsule"),
                            )
                            .put(
                                "progressInfo",
                                JSONObject()
                                    .put("progress", progress)
                                    .put("colorReach", ACCENT)
                                    .put("colorUnReach", ACCENT_UNREACH)
                                    .put("isCCW", true),
                            ),
                    ),
                ),
        )
        return root.toString()
    }

    private fun actionIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            android.content.Intent(context, AutomationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun launchIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 通知小图标转 Icon（pics bundle 需要小尺寸位图图标）。 */
    private fun simpleIcon(context: Context): android.graphics.drawable.Icon? = runCatching {
        val drawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_stat_chenlu)
        if (drawable == null) return null
        val size = 88
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        android.graphics.drawable.Icon.createWithBitmap(bitmap)
    }.getOrNull()
}
