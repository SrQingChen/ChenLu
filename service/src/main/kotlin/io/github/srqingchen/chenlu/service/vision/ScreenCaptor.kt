package io.github.srqingchen.chenlu.service.vision

import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.service.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕捕获（MediaProjection → ImageReader 单帧按需获取）。
 * Android 14+ 要求：用户同意后必须先启动 mediaProjection 类型前台服务再取 Projection。
 */
object ScreenCaptor {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val screenWidth: Int get() = width
    val screenHeight: Int get() = height

    fun setup(mediaProjection: MediaProjection, metrics: DisplayMetrics) {
        release()
        width = metrics.widthPixels
        height = metrics.heightPixels
        projection = mediaProjection
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = mediaProjection.createVirtualDisplay(
            "chenlu-captor", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null,
        )
        // 预热首帧（首帧常为空）
        repeat(2) { runCatching { capture() } }
        _ready.value = true
        ChenLuLog.i("vision", "屏幕捕获就绪：${width}x${height}")
    }

    /** 取最新一帧（ARGB_8888），失败/无帧返回 null。 */
    fun capture(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val full = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
            )
            full.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(full, 0, 0, width, height)
        } catch (t: Throwable) {
            ChenLuLog.w("vision", "取帧失败: ${t.message}")
            null
        } finally {
            image.close()
        }
    }

    fun release() {
        _ready.value = false
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
    }
}

/** mediaProjection 类型前台服务：承载屏幕捕获生命周期。 */
class VisionCaptureService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chenlu)
            .setContentTitle("尘露 · 屏幕捕获中")
            .setContentText("视觉触发需要捕获屏幕内容（本地处理，不上传）")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                ScreenCaptor.release()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data != null) {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    val projection = runCatching { manager?.getMediaProjection(resultCode, data) }.getOrNull()
                    if (projection == null) {
                        ChenLuLog.e("vision", "获取 MediaProjection 失败")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    val metrics = resources.displayMetrics
                    ScreenCaptor.setup(projection, metrics)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    companion object {
        private const val CHANNEL_ID = "chenlu_vision"
        private const val NOTIF_ID = 2002
        private const val ACTION_STOP = "io.github.srqingchen.chenlu.action.VISION_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, VisionCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VisionCaptureService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
