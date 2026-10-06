package com.logiclick.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.logiclick.app.data.ConfigStore
import com.logiclick.app.MainActivity
import com.logiclick.app.model.ApiCommand
import com.logiclick.app.model.NormalizedPoint
import com.logiclick.app.model.WorkflowConfig
import com.logiclick.app.network.CommandApi
import com.logiclick.app.security.CommandVerifier
import java.util.concurrent.Executors
import kotlin.random.Random

class LogiclickAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private lateinit var store: ConfigStore
    private var busy = false
    private var polling = false
    private var consecutiveApiFailures = 0
    private var notificationVisible = false

    override fun onServiceConnected() {
        store = ConfigStore(this)
        instance = this
        createNotificationChannel()
        store.appendLog("Accessibility service connected")
        schedulePoll(1_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { busy = false }

    override fun onDestroy() {
        instance = null
        hideArmedNotification()
        main.removeCallbacksAndMessages(null)
        network.shutdownNow()
        super.onDestroy()
    }

    private fun schedulePoll(delayMs: Long = 3_000) {
        main.postDelayed({ pollOnce() }, delayMs)
    }

    private fun pollOnce() {
        updateArmedNotification()
        if (polling || busy || !store.armed || store.endpoint.isBlank()) {
            schedulePoll(if (store.armed) 3_000 else 1_000)
            return
        }
        polling = true
        network.execute {
            val result = CommandApi.fetch(store.endpoint)
            main.post {
                polling = false
                result.fold(
                    onSuccess = { command ->
                        if (consecutiveApiFailures > 0) store.appendLog("API connection restored")
                        consecutiveApiFailures = 0
                        command?.let(::acceptCommand)
                        schedulePoll()
                    },
                    onFailure = { error ->
                        consecutiveApiFailures += 1
                        if (consecutiveApiFailures == 1 || consecutiveApiFailures % 5 == 0) {
                            store.appendLog("API unavailable: ${error.message ?: "connection error"}")
                        }
                        val backoff = (3_000L * (1L shl minOf(consecutiveApiFailures, 4)))
                            .coerceAtMost(60_000L)
                        schedulePoll(backoff)
                    }
                )
            }
        }
    }

    private fun acceptCommand(command: ApiCommand) {
        val config = store.workflow ?: run {
            store.appendLog("Rejected command: no workflow configured")
            return
        }
        if (busy) return
        command.basicValidation()?.let {
            store.appendLog("Rejected command: $it")
            return
        }
        if (command.workflowName != config.name) return
        if (store.hasSeen(command.commandId)) return
        if (!CommandVerifier.verify(command, store.publicKeyBase64)) {
            store.appendLog("Rejected command: invalid signature")
            return
        }
        if (!isTargetActive(config)) return

        store.markSeen(command.commandId)
        busy = true
        store.appendLog("Command accepted: ${command.commandId.take(24)}")
        capture { captureResult ->
            captureResult.fold(
                onSuccess = { bitmap -> inspectThenRun(bitmap, config, command) },
                onFailure = { finish(false, "Screenshot failed") }
            )
        }
    }

    private fun inspectThenRun(bitmap: Bitmap, config: WorkflowConfig, command: ApiCommand) {
        val rect = config.ocrRegion.toPixels(bitmap.width, bitmap.height)
        val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = rect.width().toInt().coerceIn(1, bitmap.width - left)
        val height = rect.height().toInt().coerceIn(1, bitmap.height - top)
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        bitmap.recycle()
        OcrEngine.read(crop) { result ->
            crop.recycle()
            val text = result.getOrNull()
            if (text == null) {
                finish(false, "OCR failed")
                return@read
            }
            if (!Regex(config.expectedTextRegex).containsMatchIn(text)) {
                finish(false, "OCR condition did not match")
                return@read
            }
            if (!isTargetActive(config)) {
                finish(false, "Target app changed before input")
                return@read
            }
            tap(config.inputPoint) { inputTapped ->
                if (!inputTapped) {
                    finish(false, "Input tap was cancelled")
                    return@tap
                }
                main.postDelayed({
                    if (!isTargetActive(config)) {
                        finish(false, "Target app changed before typing")
                        return@postDelayed
                    }
                    typeFocusedText(command.number) { typed ->
                        if (!typed || !isTargetActive(config)) {
                            finish(false, "Number field was not accessible")
                            return@typeFocusedText
                        }
                        main.postDelayed({
                            if (!isTargetActive(config)) {
                                finish(false, "Target app changed before final action")
                            } else {
                                tap(config.submitPoint) { submitted ->
                                    finish(submitted, if (submitted) "Workflow completed" else "Final tap was cancelled")
                                }
                            }
                        }, randomDelay(config))
                    }
                }, randomDelay(config))
            }
        }
    }

    private fun randomDelay(config: WorkflowConfig): Long =
        Random.nextLong(config.minDelayMs, config.maxDelayMs + 1)

    private fun tap(point: NormalizedPoint, done: (Boolean) -> Unit) {
        val metrics = resources.displayMetrics
        val path = Path().apply { moveTo(point.x * metrics.widthPixels, point.y * metrics.heightPixels) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, main)
        if (!accepted) done(false)
    }

    private fun setFocusedText(value: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        return focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        )
    }

    private fun typeFocusedText(value: String, done: (Boolean) -> Unit) {
        var length = 1
        fun typeNext() {
            if (!setFocusedText(value.substring(0, length))) {
                done(false)
                return
            }
            if (length == value.length) {
                done(true)
                return
            }
            length += 1
            // Bounded pacing keeps UI work serialized and prevents event flooding.
            main.postDelayed(::typeNext, Random.nextLong(70, 161))
        }
        typeNext()
    }

    private fun isTargetActive(config: WorkflowConfig): Boolean =
        rootInActiveWindow?.packageName?.toString() == config.targetPackage

    private fun finish(success: Boolean, message: String) {
        busy = false
        store.appendLog(if (success) message else "Stopped: $message")
        updateArmedNotification()
    }

    fun stopAutomation() {
        busy = false
        main.removeCallbacksAndMessages(null)
        hideArmedNotification()
        schedulePoll(1_000)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Armed automation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when a Logiclick workflow can receive signed commands"
                setShowBadge(false)
            }
        )
    }

    private fun updateArmedNotification() {
        if (!store.armed) {
            hideArmedNotification()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = PendingIntent.getActivity(
            this, 100, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 101, Intent(this, StopAutomationReceiver::class.java).setAction(StopAutomationReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val workflowName = store.workflow?.name ?: "workflow"
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Logiclick is armed")
            .setContentText("Waiting for signed commands for $workflowName")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopIntent).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        notificationVisible = true
    }

    private fun hideArmedNotification() {
        if (!notificationVisible) return
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        notificationVisible = false
    }

    fun capture(callback: (Result<Bitmap>) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, { runnable -> main.post(runnable) }, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                callback(runCatching { screenshot.toBitmap() })
            }

            override fun onFailure(errorCode: Int) {
                callback(Result.failure(IllegalStateException("Screenshot failed: $errorCode")))
            }
        })
    }

    private fun ScreenshotResult.toBitmap(): Bitmap {
        val buffer: HardwareBuffer = hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                ?: error("Could not read screenshot buffer")
            hardware.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            buffer.close()
        }
    }

    companion object {
        private const val CHANNEL_ID = "logiclick_armed"
        private const val NOTIFICATION_ID = 1201
        @Volatile var instance: LogiclickAccessibilityService? = null
            private set
    }
}
