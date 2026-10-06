package com.logiclick.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
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

    override fun onServiceConnected() {
        store = ConfigStore(this)
        instance = this
        schedulePoll(1_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { busy = false }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        network.shutdownNow()
        super.onDestroy()
    }

    private fun schedulePoll(delayMs: Long = 3_000) {
        main.postDelayed({ pollOnce() }, delayMs)
    }

    private fun pollOnce() {
        if (polling || busy || !store.armed || store.endpoint.isBlank()) {
            schedulePoll()
            return
        }
        polling = true
        network.execute {
            val result = CommandApi.fetch(store.endpoint)
            main.post {
                polling = false
                result.getOrNull()?.let(::acceptCommand)
                schedulePoll()
            }
        }
    }

    private fun acceptCommand(command: ApiCommand) {
        val config = store.workflow ?: return
        if (busy || command.basicValidation() != null || command.workflowName != config.name) return
        if (store.hasSeen(command.commandId)) return
        if (!CommandVerifier.verify(command, store.publicKeyBase64)) return
        if (rootInActiveWindow?.packageName?.toString() != config.targetPackage) return

        store.markSeen(command.commandId)
        busy = true
        capture { captureResult ->
            captureResult.fold(
                onSuccess = { bitmap -> inspectThenRun(bitmap, config, command) },
                onFailure = { busy = false }
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
            if (text == null || !Regex(config.expectedTextRegex).containsMatchIn(text)) {
                busy = false
                return@read
            }
            if (!isTargetActive(config)) {
                busy = false
                return@read
            }
            tap(config.inputPoint) {
                main.postDelayed({
                    if (!isTargetActive(config)) {
                        busy = false
                        return@postDelayed
                    }
                    typeFocusedText(command.number) { typed ->
                        if (!typed || !isTargetActive(config)) {
                            busy = false
                            return@typeFocusedText
                        }
                        main.postDelayed({
                            if (!isTargetActive(config)) {
                                busy = false
                            } else {
                                tap(config.submitPoint) { busy = false }
                            }
                        }, randomDelay(config))
                    }
                }, randomDelay(config))
            }
        }
    }

    private fun randomDelay(config: WorkflowConfig): Long =
        Random.nextLong(config.minDelayMs, config.maxDelayMs + 1)

    private fun tap(point: NormalizedPoint, done: () -> Unit) {
        val metrics = resources.displayMetrics
        val path = Path().apply { moveTo(point.x * metrics.widthPixels, point.y * metrics.heightPixels) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done()
            override fun onCancelled(gestureDescription: GestureDescription?) { busy = false }
        }, main)
        if (!accepted) busy = false
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
        @Volatile var instance: LogiclickAccessibilityService? = null
            private set
    }
}
