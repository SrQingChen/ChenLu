package io.github.srqingchen.chenlu.service.island

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.service.AutomationController
import io.github.srqingchen.chenlu.service.AutomationService
import io.github.srqingchen.chenlu.service.R
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 原生超级岛发布器（MAA-Meow 同款路线）：
 * 原生 Notification extras 塞 `miui.focus.param`（V3 模板），澎湃 OS 3 以岛样式渲染。
 *
 * 两种形态：
 * - 待命岛（常驻）：离开应用界面即上岛，回应用自动收起；
 * - 运行岛（任务中）：进度环显示真实进度（次数/总次数 或 时长/总时长），1Hz 刷新。
 *
 * 通知栏下滑展开 = 简易控制面板（RemoteViews 自定义大视图：启停 / 间隔± / 打开应用）。
 *
 * 「兼容模式」：非白名单应用直发会被云端鉴权摘岛；岛展示期间经 Shizuku 临时切断
 * com.xiaomi.xmsf 联网令鉴权 fail-open。副作用：期间全机小米推送延迟，岛收起即恢复
 * （状态落盘防遗留）。
 */
object FocusIslandPublisher {

    private const val CHANNEL_ID = "chenlu_island"
    private const val NOTIF_ID = 2001
    private const val ACCENT = "#00897B"
    private const val ACCENT_UNREACH = "#33FFFFFF"

    /** 任务状态上岛开关。 */
    @Volatile
    var enabled = true

    /** 兼容模式（断 xmsf 令鉴权放行）默认开启；需 Shizuku 就绪。 */
    @Volatile
    var compatMode = true

    /** 常驻待命岛：离开应用界面即显示。 */
    @Volatile
    var idleEnabled = true

    /**
     * 由 Application 的 ActivityLifecycleCallbacks 维护。
     * 默认 false：进程可能由悬浮球/服务拉起（无 Activity 生命周期），此时视为后台。
     */
    @Volatile
    var appForeground = false

    private val gateExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var gateActive = false

    @Volatile
    private var channelReady = false

    // ---------- 生命周期入口 ----------

    /** 应用退到后台：非运行态则上待命岛。 */
    fun onAppBackground(context: Context) {
        if (!idleEnabled) return
        if (AutomationController.state.value.running) return
        publishIdle(context)
    }

    /** 应用回到前台：非运行态则收起待命岛并恢复 xmsf。 */
    fun onAppForeground(context: Context) {
        if (AutomationController.state.value.running) return
        dismiss(context)
    }

    /** 任务停止后：前台则收起，后台则回到待命岛。 */
    fun onTaskStopped(context: Context) {
        if (!appForeground && idleEnabled) {
            publishIdle(context)
        } else {
            dismiss(context)
        }
    }

    // ---------- 发布 ----------

    fun publishIdle(context: Context) {
        if (!idleEnabled) return
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val config = AutomationController.state.value.config
        engageGateIfNeeded(appContext)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            buildNotification(
                appContext,
                title = "尘露 · 待命",
                content = idleSummary(config),
                progress = 0,
                progressText = "轻点「开始」随时连点",
                actionsRunning = false,
            ),
        )
    }

    fun publishRunning(
        context: Context,
        count: Long,
        config: TapConfig,
        engineId: String?,
        elapsedMs: Long,
    ) {
        if (!enabled) return
        val appContext = context.applicationContext
        ensureChannel(appContext)
        engageGateIfNeeded(appContext)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            buildNotification(
                appContext,
                title = "尘露 · 运行中",
                content = runningSummary(config, engineId),
                progress = progressOf(count, config, elapsedMs),
                progressText = progressText(count, config, elapsedMs),
                actionsRunning = true,
            ),
        )
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        releaseGate(appContext)
    }

    /** 服务启动时调用：仅当本进程内存态认为未断网（进程刚重建）时，清理上次遗留的断网状态。 */
    fun restoreGateIfNeeded(context: Context) {
        // 同进程内 gateActive 是权威状态：岛正在展示（断网生效中）时绝不能“恢复”
        if (gateActive) return
        val flag = gateFlagFile(context)
        if (flag.exists()) {
            gateExecutor.execute {
                val err = ShizukuManager.xmsfGate(false)
                if (err == null) {
                    flag.delete()
                    ChenLuLog.i("island", "已恢复上次遗留的 xmsf 断网状态")
                }
            }
        }
    }

    // ---------- 进度计算 ----------

    private fun progressOf(count: Long, config: TapConfig, elapsedMs: Long): Int = when {
        config.totalClicks > 0 -> ((count * 100) / config.totalClicks).toInt().coerceIn(0, 100)
        config.totalDurationMs > 0 -> ((elapsedMs * 100) / config.totalDurationMs).toInt().coerceIn(0, 100)
        else -> (count % 100).toInt()
    }

    private fun progressText(count: Long, config: TapConfig, elapsedMs: Long): String = when {
        config.totalClicks > 0 -> "$count / ${config.totalClicks} 次"
        config.totalDurationMs > 0 -> "${fmtDuration(elapsedMs)} / ${fmtDuration(config.totalDurationMs)}"
        else -> "$count 次"
    }

    private fun fmtDuration(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun idleSummary(config: TapConfig): String = buildString {
        append(config.targets.size).append(" 个目标 · ")
        append(config.intervalMs).append("ms")
        if (config.hasFinishCondition()) append(" · 有限量")
    }

    private fun runningSummary(config: TapConfig, engineId: String?): String = buildString {
        append(config.intervalMs).append("ms")
        if (config.targets.size > 1) {
            append(" · ").append(config.targets.size).append("点")
            append(
                if (config.order == io.github.srqingchen.chenlu.core.model.TargetOrder.RANDOM) "随机" else "顺序",
            )
        }
        engineId?.let { append(" · ").append(it) }
    }

    // ---------- 兼容模式网关 ----------

    private fun engageGateIfNeeded(context: Context) {
        if (!compatMode || gateActive) return
        if (ShizukuManager.injector == null) return
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
            // 延迟恢复，确保岛已收起
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

    // ---------- 通知构建 ----------

    private fun ensureChannel(context: Context) {
        if (channelReady) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "超级岛状态", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "尘露待命/任务状态上岛（澎湃 OS 3）"
                setSound(null, null)
                enableVibration(false)
            },
        )
        channelReady = true
    }

    private fun buildNotification(
        context: Context,
        title: String,
        content: String,
        progress: Int,
        progressText: String,
        actionsRunning: Boolean,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chenlu)
            .setContentTitle(title)
            .setContentText("$content · $progressText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(android.app.Notification.CATEGORY_PROGRESS)
            .addAction(
                0,
                if (actionsRunning) "暂停" else "开始",
                actionIntent(context, AutomationService.ACTION_TOGGLE),
            )
            .addAction(0, "停止", actionIntent(context, AutomationService.ACTION_STOP))
            .setContentIntent(launchIntent(context))

        // 通知栏下拉展开 = 简易控制面板
        builder.setCustomBigContentView(buildPanel(context, title, content, progressText, actionsRunning))

        val icon = simpleIcon(context)
        val extras = Bundle().apply {
            putString("miui.focus.param", buildFocusJson(title, content, progressText, progress))
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

    /** 通知栏展开的简易控制面板：状态 / 进度 / 启停 / 间隔± / 打开应用。 */
    private fun buildPanel(
        context: Context,
        title: String,
        content: String,
        progressText: String,
        running: Boolean,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.island_panel).apply {
        setTextViewText(R.id.panel_title, title)
        setTextViewText(R.id.panel_detail, "$content · $progressText")
        setTextViewText(R.id.panel_toggle, if (running) "暂停" else "开始")
        setOnClickPendingIntent(R.id.panel_toggle, actionIntent(context, AutomationService.ACTION_TOGGLE))
        setOnClickPendingIntent(R.id.panel_minus, actionIntent(context, AutomationService.ACTION_INTERVAL_MINUS))
        setOnClickPendingIntent(R.id.panel_plus, actionIntent(context, AutomationService.ACTION_INTERVAL_PLUS))
        setOnClickPendingIntent(R.id.panel_open, launchIntent(context))
    }

    /** V3 焦点岛参数（business 复用官方 download_progress 模板，进度环真实进度）。 */
    private fun buildFocusJson(
        title: String,
        content: String,
        progressText: String,
        progress: Int,
    ): String {
        val root = JSONObject().put(
            "param_v2",
            JSONObject()
                .put("business", "download_progress")
                .put("updatable", true)
                .put("notifyId", NOTIF_ID.toString())
                .put("timeout", 1440)
                .put("sequence", SystemClock.elapsedRealtime())
                .put("aodTitle", "$title $progressText")
                .put("ticker", "$title $content")
                .put("reopen", "reopen")
                .put("islandProperty", 1)
                .put("islandTimeout", 86400)
                .put("dismissIsland", false)
                .put(
                    "chatInfo",
                    JSONObject().put("title", title).put("content", "$content · $progressText"),
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
                                    .put("content", "$content · $progressText")
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
            Intent(context, AutomationService::class.java).setAction(action),
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
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_stat_chenlu) ?: return null
        val size = 88
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        android.graphics.drawable.Icon.createWithBitmap(bitmap)
    }.getOrNull()
}
