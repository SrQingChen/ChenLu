package io.github.srqingchen.chenlu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.srqingchen.chenlu.core.model.Point

/**
 * 前台服务：任务循环宿主 + 常驻控制通知（specialUse 类型）。
 * 通知本身即 M0 的“传统通知栏兜底”控制面；M2 起 IslandPublisher 接管优先展示。
 */
class AutomationService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        AutomationController.attachScope(lifecycleScope)
        AutomationController.attachContext(this)
        io.github.srqingchen.chenlu.service.island.FocusIslandPublisher.restoreGateIfNeeded(this)
        ensureDefaultTarget()
        createChannel()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 每次外部 startForegroundService 都必须再次 startForeground
        startInForeground()
        when (intent?.action) {
            ACTION_TOGGLE -> AutomationController.toggle()
            ACTION_STOP -> {
                AutomationController.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        AutomationController.stop()
        super.onDestroy()
    }

    /** 未设置目标点时默认取屏幕中心（多目标经屏幕选点设置）。 */
    private fun ensureDefaultTarget() {
        if (AutomationController.state.value.config.targets.isNotEmpty()) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        AutomationController.updateConfig {
            it.copy(targets = listOf(Point(bounds.width() / 2f, bounds.height() / 2f)))
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.chenlu_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.chenlu_notification_channel_desc) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chenlu)
            .setContentTitle(getString(R.string.chenlu_notification_title))
            .setContentText(getString(R.string.chenlu_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .addAction(0, getString(R.string.chenlu_action_toggle), actionPendingIntent(ACTION_TOGGLE))
            .addAction(0, getString(R.string.chenlu_action_stop), actionPendingIntent(ACTION_STOP))
            .build()

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, AutomationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_ID = "chenlu_automation"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE = "io.github.srqingchen.chenlu.action.TOGGLE"
        const val ACTION_STOP = "io.github.srqingchen.chenlu.action.STOP"

        /** 确保 FGS 存活并转发控制动作（服务未启动时先启动）。 */
        fun toggle(context: Context) = dispatch(context, ACTION_TOGGLE)

        fun stop(context: Context) = dispatch(context, ACTION_STOP)

        private fun dispatch(context: Context, action: String) {
            val intent = Intent(context, AutomationService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
