package com.logiclick.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.logiclick.app.automation.LogiclickAccessibilityService
import com.logiclick.app.data.ConfigStore
import com.logiclick.app.ui.SetupActivity
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: ConfigStore
    private lateinit var status: TextView
    private lateinit var armed: Switch
    private val main = Handler(Looper.getMainLooper())
    private var pendingCapturePath: String? = null
    private var pendingCapturePackage: String? = null
    private var setupEditorOpened = false
    private var activityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)

        val endpoint = EditText(this).apply {
            hint = "HTTPS command endpoint"
            setText(store.endpoint)
        }
        val publicKey = EditText(this).apply {
            hint = "Ed25519 public key (Base64 X.509)"
            setText(store.publicKeyBase64)
        }
        val setupPackage = EditText(this).apply {
            hint = "Target app package for visual setup"
            setText(store.workflow?.targetPackage.orEmpty())
        }
        status = TextView(this).apply { textSize = 16f }
        armed = Switch(this).apply {
            text = "Arm saved workflow"
            isChecked = store.armed
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && (store.workflow == null || endpoint.text.isBlank() || publicKey.text.isBlank())) {
                    isChecked = false
                    Toast.makeText(this@MainActivity, "Save API settings and a workflow first", Toast.LENGTH_LONG).show()
                } else {
                    store.armed = enabled
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
                store.endpoint = url
                store.publicKeyBase64 = publicKey.text.toString()
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
                refreshStatus()
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            addView(TextView(this@MainActivity).apply { text = "Logiclick"; textSize = 30f })
            addView(TextView(this@MainActivity).apply {
                text = "Signed, allowlisted OCR workflows. Keep this visibly disclosed to the device user."
            })
            addView(endpoint)
            addView(publicKey)
            addView(saveApi)
            addView(accessibility)
            addView(setupPackage)
            addView(setup)
            addView(armed)
            addView(stop)
            addView(status)
        })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        maybeOpenSetupEditor()
        if (::status.isInitialized) refreshStatus()
    }

    override fun onPause() {
        activityResumed = false
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
                    maybeOpenSetupEditor()
                }, onFailure = {
                    pendingCapturePath = null
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
        }
        armed.isChecked = store.armed
    }
}
