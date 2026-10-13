package com.logiclick.app.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.logiclick.app.MainActivity
import com.logiclick.app.data.ConfigStore
import com.logiclick.app.network.CommandApi
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MirrorService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val uploading = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var endpoint = ""
    private var token = ""
    private var deviceId = ""
    private var intervalMs = 1_000L
    private var jpegQuality = 60
    private var maxWidth = 720

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null) return START_NOT_STICKY

        endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 1_000L).coerceIn(200L, 10_000L)
        jpegQuality = intent.getIntExtra(EXTRA_JPEG_QUALITY, 60).coerceIn(20, 90)
        maxWidth = intent.getIntExtra(EXTRA_MAX_WIDTH, 720).coerceIn(320, 1_440)
        if (!CommandApi.isAllowedEndpoint(endpoint)) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        ConfigStore(this).appendLog("Screen mirror started")
        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val thread = HandlerThread("logiclick-mirror-capture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        display = projection?.createVirtualDisplay(
            "LogiclickMirror",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )
        imageReader.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (now - lastFrameAt < intervalMs || !uploading.compareAndSet(false, true)) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = image.planes.first()
                val rowPadding = plane.rowStride - plane.pixelStride * image.width
                val padded = Bitmap.createBitmap(
                    image.width + rowPadding / plane.pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                plane.buffer.rewind()
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (cropped !== padded) padded.recycle()
                val frame = if (cropped.width > maxWidth) {
                    val scaledHeight = (cropped.height * (maxWidth.toFloat() / cropped.width)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(cropped, maxWidth, scaledHeight, true).also { cropped.recycle() }
                } else cropped
                val bytes = ByteArrayOutputStream().use { output ->
                    frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
                    frame.recycle()
                    output.toByteArray()
                }
                uploadExecutor.execute { upload(bytes, now) }
            } catch (_: Throwable) {
                uploading.set(false)
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun upload(bytes: ByteArray, capturedAt: Long) {
        try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 7_000
                connection.readTimeout = 7_000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "image/jpeg")
                connection.setRequestProperty("X-Logiclick-Device-ID", deviceId)
                connection.setRequestProperty("X-Captured-At", capturedAt.toString())
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write(bytes) }
                if (connection.responseCode !in 200..299) {
                    ConfigStore(this).appendLog("Mirror upload returned HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: Throwable) {
            ConfigStore(this).appendLog("Mirror upload failed: ${error.message ?: "connection error"}")
        } finally {
            uploading.set(false)
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows whenever Logiclick is sending screen frames"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 201, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 202, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Logiclick screen sharing is active")
            .setContentText("Sending visible screen frames to the selected endpoint")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "STOP", stop).build())
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        captureThread?.quitSafely()
        uploadExecutor.shutdownNow()
        ConfigStore(this).appendLog("Screen mirror stopped")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.logiclick.app.mirror.START"
        const val ACTION_STOP = "com.logiclick.app.mirror.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_INTERVAL_MS = "intervalMs"
        const val EXTRA_JPEG_QUALITY = "jpegQuality"
        const val EXTRA_MAX_WIDTH = "maxWidth"
        private const val CHANNEL_ID = "logiclick_screen_share"
        private const val NOTIFICATION_ID = 1202

        @Volatile var isRunning = false
            private set
    }
}
