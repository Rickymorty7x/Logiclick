package com.logiclick.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.logiclick.app.automation.LogiclickAccessibilityService
import com.logiclick.app.data.ConfigStore
import com.logiclick.app.security.CommandVerifier
import com.logiclick.app.ui.SetupActivity
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: ConfigStore
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var armed: Switch
    private val main = Handler(Looper.getMainLooper())
    private var pendingCapturePath: String? = null
    private var pendingCapturePackage: String? = null
    private var setupEditorOpened = false
    private var activityResumed = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!activityResumed) return
            refreshStatus()
            main.postDelayed(this, 1_500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)

        val endpoint = EditText(this).apply {
            hint = "HTTPS command endpoint"
            setText(store.endpoint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val publicKey = EditText(this).apply {
            hint = "Ed25519 public key (Base64 X.509)"
            setText(store.publicKeyBase64)
            minLines = 2
            maxLines = 4
            typeface = Typeface.MONOSPACE
        }
        val setupPackage = EditText(this).apply {
            hint = "Target app package for visual setup"
            setText(store.workflow?.targetPackage.orEmpty())
        }
        status = TextView(this).apply { textSize = 16f }
        logs = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
        }
        armed = Switch(this).apply {
            text = "Arm signed workflow"
            textSize = 17f
            isChecked = store.armed
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && (store.workflow == null || endpoint.text.isBlank() || publicKey.text.isBlank())) {
                    isChecked = false
                    Toast.makeText(this@MainActivity, "Save API settings and a workflow first", Toast.LENGTH_LONG).show()
                } else if (enabled && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                    Toast.makeText(this@MainActivity, "Allow notifications, then arm again", Toast.LENGTH_LONG).show()
                } else {
                    store.armed = enabled
                    store.appendLog(if (enabled) "Workflow armed by user" else "Workflow disarmed by user")
                    refreshStatus()
                }
            }
        }
        val saveApi = Button(this).apply {
            text = "Save API settings"
            setOnClickListener {
                val url = endpoint.text.toString().trim()
                if (!url.startsWith("https://")) {
                    Toast.makeText(this@MainActivity, "The endpoint must use HTTPS", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri?.host.isNullOrBlank() || uri?.userInfo != null) {
                    Toast.makeText(this@MainActivity, "Enter a valid HTTPS URL without embedded credentials", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val key = publicKey.text.toString().trim()
                if (!CommandVerifier.isValidPublicKey(key)) {
                    Toast.makeText(this@MainActivity, "The Ed25519 public key is not valid", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                store.endpoint = url
                store.publicKeyBase64 = key
                store.appendLog("API settings updated")
                Toast.makeText(this@MainActivity, "API settings saved", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
        val accessibility = Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val setup = Button(this).apply {
            text = "Open target app and capture for setup"
            setOnClickListener { captureForSetup(setupPackage.text.toString().trim()) }
        }
        val stop = Button(this).apply {
            text = "STOP AND DISARM"
            setOnClickListener {
                store.armed = false
                armed.isChecked = false
                LogiclickAccessibilityService.instance?.stopAutomation()
                refreshStatus()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 64)
            addView(TextView(this@MainActivity).apply {
                text = "Logiclick"
                textSize = 32f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Production preview · v${BuildConfig.VERSION_NAME}\nSigned, allowlisted OCR workflows that remain visible and user-controlled."
                setTextColor(Color.DKGRAY)
                setPadding(0, 4, 0, 24)
            })
            addView(sectionTitle("STATUS"))
            addView(status)
            addView(armed)
            addView(stop)
            addView(sectionTitle("CONNECTION"))
            addView(endpoint)
            addView(publicKey)
            addView(saveApi)
            addView(sectionTitle("VISUAL WORKFLOW"))
            addView(accessibility)
            addView(setupPackage)
            addView(setup)
            addView(sectionTitle("RECENT ACTIVITY"))
            addView(logs)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        maybeOpenSetupEditor()
        if (::status.isInitialized) {
            refreshStatus()
            main.removeCallbacks(refreshRunnable)
            main.post(refreshRunnable)
        }
    }

    override fun onPause() {
        activityResumed = false
        main.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun maybeOpenSetupEditor() {
        val capture = pendingCapturePath
        if (activityResumed && capture != null && !setupEditorOpened) {
            setupEditorOpened = true
            startActivity(Intent(this, SetupActivity::class.java).apply {
                putExtra(SetupActivity.EXTRA_CAPTURE, capture)
                putExtra(SetupActivity.EXTRA_PACKAGE, pendingCapturePackage)
            })
            pendingCapturePath = null
            pendingCapturePackage = null
        }
    }

    private fun captureForSetup(targetPackage: String) {
        val service = LogiclickAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Enable Logiclick in Accessibility settings first", Toast.LENGTH_LONG).show()
            return
        }
        if (targetPackage.isBlank()) {
            Toast.makeText(this, "Enter the target app package first", Toast.LENGTH_LONG).show()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launch == null) {
            Toast.makeText(this, "That installed app package was not found", Toast.LENGTH_LONG).show()
            return
        }
        setupEditorOpened = false
        Toast.makeText(this, "Target opens now. Wait for capture, then return to Logiclick.", Toast.LENGTH_LONG).show()
        startActivity(launch)
        main.postDelayed({
            service.capture { result ->
                result.fold(onSuccess = { bitmap ->
                    val file = File(cacheDir, "setup-screen.png")
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    pendingCapturePath = file.absolutePath
                    pendingCapturePackage = targetPackage
                    store.appendLog("Setup screenshot captured for $targetPackage")
                    maybeOpenSetupEditor()
                }, onFailure = {
                    pendingCapturePath = null
                    store.appendLog("Setup screenshot failed: ${it.message ?: "unknown error"}")
                })
            }
        }, 2_000)
    }

    private fun refreshStatus() {
        val workflow = store.workflow
        status.text = buildString {
            append("Service: ")
            append(if (LogiclickAccessibilityService.instance == null) "disabled" else "enabled")
            append("\nWorkflow: ")
            append(workflow?.name ?: "not configured")
            workflow?.let { append(" → ${it.targetPackage}") }
            append("\nAutomation: ")
            append(if (store.armed) "ARMED" else "stopped")
            append("\nAPI: ")
            append(if (store.endpoint.isBlank()) "not configured" else "configured")
        }
        armed.isChecked = store.armed
        logs.text = store.recentLogs().ifEmpty { listOf("No activity yet") }.joinToString("\n")
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF215CFF.toInt())
        gravity = Gravity.START
        setPadding(0, 28, 0, 8)
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 701
    }
}
