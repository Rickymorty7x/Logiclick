package com.logiclick.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.logiclick.app.MainActivity
import com.logiclick.app.data.ConfigStore
import com.logiclick.app.model.ApiCommand
import com.logiclick.app.model.CoordinateArea
import com.logiclick.app.model.RemoteAction
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

    private fun schedulePoll(delayMs: Long = 1_000) {
        main.postDelayed(::pollOnce, delayMs)
    }

    private fun pollOnce() {
        updateArmedNotification()
        val missingBuiltInCredentials = !store.directEndpoint && store.deviceToken.isBlank()
        if (polling || busy || !store.armed || store.endpoint.isBlank() || missingBuiltInCredentials) {
            schedulePoll(if (store.armed) 1_000 else 2_000)
            return
        }
        polling = true
        network.execute {
            val result = CommandApi.fetch(store.endpoint, store.deviceId, store.deviceToken, store.directEndpoint)
            main.post {
                polling = false
                result.fold(
                    onSuccess = { command ->
                        if (consecutiveApiFailures > 0) store.appendLog("API connection restored")
                        consecutiveApiFailures = 0
                        command?.let(::acceptCommand)
                        if (!busy) schedulePoll()
                    },
                    onFailure = { error ->
                        consecutiveApiFailures += 1
                        if (consecutiveApiFailures == 1 || consecutiveApiFailures % 5 == 0) {
                            store.appendLog("API unavailable: ${error.message ?: "connection error"}")
                        }
                        val backoff = (2_000L * (1L shl minOf(consecutiveApiFailures, 5))).coerceAtMost(60_000L)
                        schedulePoll(backoff)
                    }
                )
            }
        }
    }

    private fun acceptCommand(command: ApiCommand) {
        if (busy) return
        command.basicValidation()?.let {
            store.appendLog("Rejected command: $it")
            return
        }
        if (store.hasSeen(command.commandId)) return
        if (!CommandVerifier.verify(command, store.publicKeyBase64)) {
            store.appendLog("Rejected command: invalid signature")
            return
        }

        store.markSeen(command.commandId)
        busy = true
        store.appendLog("Accepted ${command.commandId.take(24)}")
        when (val action = command.action) {
            is RemoteAction.Gesture -> performGesture(action) { finish(it, if (it) "Gesture completed" else "Gesture cancelled") }
            is RemoteAction.Global -> finish(performGlobal(action.name), "Global action ${action.name}")
            is RemoteAction.LaunchApp -> finish(launchApp(action.packageName), "Launch ${action.packageName}")
        }
    }

    private fun performGesture(action: RemoteAction.Gesture, done: (Boolean) -> Unit) {
        val foreground = rootInActiveWindow?.packageName?.toString()
        if (foreground == null || isProtectedPackage(foreground)) {
            store.appendLog("Blocked gesture on protected screen: ${foreground ?: "unknown"}")
            done(false)
            return
        }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (action.coordinateSpace !in setOf("normalized", "pixels")) {
            done(false)
            return
        }
        val gesture = runCatching {
            GestureDescription.Builder().apply {
                action.strokes.forEach { stroke ->
                    val path = Path()
                    stroke.points.forEachIndexed { index, area ->
                        val point = randomPoint(area, action.coordinateSpace, width, height)
                        if (index == 0) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
                    }
                    addStroke(GestureDescription.StrokeDescription(path, stroke.startDelayMs, stroke.durationMs.random()))
                }
            }.build()
        }.getOrElse {
            store.appendLog("Invalid gesture: ${it.message ?: "bad coordinates"}")
            done(false)
            return
        }
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, main)
        if (!accepted) done(false)
    }

    private fun randomPoint(area: CoordinateArea, space: String, width: Float, height: Float): Pair<Float, Float> {
        val scaleX = if (space == "normalized") width else 1f
        val scaleY = if (space == "normalized") height else 1f
        if (space == "normalized") {
            require(area.left >= 0f && area.top >= 0f && area.right <= 1f && area.bottom <= 1f)
        } else {
            require(area.left >= 0f && area.top >= 0f && area.right < width && area.bottom < height)
        }
        fun choose(min: Float, max: Float): Float =
            if (min == max) min else Random.nextDouble(min.toDouble(), max.toDouble()).toFloat()
        return Pair(choose(area.left, area.right) * scaleX, choose(area.top, area.bottom) * scaleY)
    }

    private fun performGlobal(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "dismiss_notification_shade" -> GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            else -> return false
        }
        return performGlobalAction(action)
    }

    private fun launchApp(packageName: String): Boolean {
        if (!packageName.matches(Regex("^[A-Za-z][A-Za-z0-9_.]{2,200}$")) || isProtectedPackage(packageName)) return false
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(launch); true }.getOrDefault(false)
    }

    private fun isProtectedPackage(packageName: String): Boolean = PROTECTED_PACKAGES.any {
        packageName == it || packageName.startsWith("$it.")
    }

    private fun finish(success: Boolean, message: String) {
        busy = false
        store.appendLog(if (success) message else "Stopped: $message")
        updateArmedNotification()
        schedulePoll(250)
    }

    fun stopAutomation() {
        busy = false
        main.removeCallbacksAndMessages(null)
        hideArmedNotification()
        schedulePoll(1_000)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote control", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows whenever Logiclick can receive signed remote-control commands"
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
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Logiclick remote control is active")
            .setContentText("Device ${store.deviceId} is waiting for signed commands")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "STOP", stopIntent).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        notificationVisible = true
    }

    private fun hideArmedNotification() {
        if (!notificationVisible) return
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        notificationVisible = false
    }

    companion object {
        private const val CHANNEL_ID = "logiclick_remote_control"
        private const val NOTIFICATION_ID = 1201
        private val PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller"
        )

        @Volatile var instance: LogiclickAccessibilityService? = null
            private set
    }
}
