package com.logiclick.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.logiclick.app.automation.LogiclickAccessibilityService
import com.logiclick.app.data.ConfigStore
import com.logiclick.app.mirror.MirrorService
import com.logiclick.app.network.CommandApi
import com.logiclick.app.security.CommandVerifier

class MainActivity : Activity() {
    private lateinit var store: ConfigStore
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var armed: Switch
    private lateinit var mirror: Switch
    private var syncingMirrorSwitch = false
    private val main = Handler(Looper.getMainLooper())
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

        val endpoint = field("API base URL", store.endpoint, InputType.TYPE_TEXT_VARIATION_URI)
        val endpointSource = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("Built-in Raspberry Pi", "Custom endpoint")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(if (store.directEndpoint) 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        if (endpoint.text.toString() != BuildConfig.DEFAULT_API_URL && endpoint.text.isNotBlank()) {
                            store.customEndpoint = endpoint.text.toString()
                        }
                        endpoint.setText(BuildConfig.DEFAULT_API_URL)
                        endpoint.isEnabled = false
                    } else {
                        endpoint.isEnabled = true
                        if (endpoint.text.toString() == BuildConfig.DEFAULT_API_URL) {
                            endpoint.setText(store.customEndpoint)
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        val deviceId = field("Device ID, for example phone-1", store.deviceId)
        val deviceToken = field("Bearer token (optional for custom endpoint)", store.deviceToken).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val publicKey = EditText(this).apply {
            hint = "Ed25519 public key (Base64 X.509)"
            setText(store.publicKeyBase64)
            minLines = 2
            maxLines = 4
            typeface = Typeface.MONOSPACE
        }
        val mirrorEndpoint = field("Screen mirror output URL", store.mirrorEndpoint, InputType.TYPE_TEXT_VARIATION_URI)
        val mirrorToken = field(
            "Mirror bearer token (defaults to device token)",
            store.mirrorToken.ifBlank { store.deviceToken }
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val mirrorInterval = field("Frame interval in ms (200–10000)", store.mirrorIntervalMs.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val mirrorQuality = field("JPEG quality (20–90)", store.mirrorJpegQuality.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val mirrorWidth = field("Maximum frame width (320–1440)", store.mirrorMaxWidth.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val autoResume = Switch(this).apply {
            text = "Keep sharing while device is on; remind after reboot"
            isChecked = store.mirrorAutoResume
            setOnCheckedChangeListener { _, checked -> store.mirrorAutoResume = checked }
        }
        status = TextView(this).apply { textSize = 16f }
        logs = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
        }
        armed = Switch(this).apply {
            text = "Receive signed action commands"
            textSize = 17f
            isChecked = store.armed
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !settingsComplete()) {
                    isChecked = false
                    toast("Save complete API settings first")
                } else if (enabled && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                    toast("Allow notifications, then arm again")
                } else {
                    store.armed = enabled
                    store.appendLog(if (enabled) "Remote control enabled by user" else "Remote control disabled by user")
                    refreshStatus()
                }
            }
        }
        mirror = Switch(this).apply {
            text = "Send screen mirror"
            textSize = 17f
            isChecked = MirrorService.isRunning
            setOnCheckedChangeListener { _, enabled ->
                if (syncingMirrorSwitch) return@setOnCheckedChangeListener
                if (enabled) requestMirrorCapture() else if (MirrorService.isRunning) {
                    startService(Intent(this@MainActivity, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
                }
            }
        }
        val saveApi = Button(this).apply {
            text = "Save connection settings"
            setOnClickListener {
                val url = endpoint.text.toString().trim().trimEnd('/')
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri?.host.isNullOrBlank() || !CommandApi.isAllowedEndpoint(url)) {
                    toast("Use HTTPS or the Pi's Tailscale 100.x address")
                    return@setOnClickListener
                }
                val id = deviceId.text.toString().trim()
                if (!id.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) {
                    toast("Device ID may use letters, numbers, underscore and dash")
                    return@setOnClickListener
                }
                val token = deviceToken.text.toString().trim()
                val direct = endpointSource.selectedItemPosition == 1
                if ((!direct && token.length !in 24..256) || token.length > 512) {
                    toast(if (direct) "Optional bearer token is too long" else "Device token must be 24–256 characters")
                    return@setOnClickListener
                }
                val key = publicKey.text.toString().trim()
                if (!CommandVerifier.isValidPublicKey(key)) {
                    toast("The Ed25519 public key is not valid")
                    return@setOnClickListener
                }
                val mirrorUrl = mirrorEndpoint.text.toString().trim()
                val interval = mirrorInterval.text.toString().toIntOrNull()
                val quality = mirrorQuality.text.toString().toIntOrNull()
                val width = mirrorWidth.text.toString().toIntOrNull()
                if (!CommandApi.isAllowedEndpoint(mirrorUrl)) {
                    toast("Enter a valid HTTPS or Tailscale mirror URL")
                    return@setOnClickListener
                }
                if (interval == null || interval !in 200..10_000 ||
                    quality == null || quality !in 20..90 ||
                    width == null || width !in 320..1_440
                ) {
                    toast("Check mirror interval, JPEG quality, and maximum width")
                    return@setOnClickListener
                }
                if (store.endpoint != url && store.armed) {
                    store.armed = false
                    armed.isChecked = false
                    LogiclickAccessibilityService.instance?.stopAutomation()
                    store.appendLog("Disarmed because API source changed")
                }
                store.endpoint = url
                store.directEndpoint = direct
                if (direct) store.customEndpoint = url
                store.deviceId = id
                store.deviceToken = token
                store.publicKeyBase64 = key
                store.mirrorEndpoint = mirrorUrl
                store.mirrorToken = mirrorToken.text.toString().trim()
                store.mirrorIntervalMs = interval!!
                store.mirrorJpegQuality = quality!!
                store.mirrorMaxWidth = width!!
                store.mirrorAutoResume = autoResume.isChecked
                store.appendLog("Connection settings updated")
                toast("Settings saved")
                refreshStatus()
            }
        }
        val accessibility = Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val stop = Button(this).apply {
            text = "STOP AND DISARM"
            setOnClickListener {
                store.armed = false
                armed.isChecked = false
                LogiclickAccessibilityService.instance?.stopAutomation()
                startService(Intent(this@MainActivity, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
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
                text = "Visible, signed remote touch control for your Android device. The phone must be unlocked. Security and permission screens are blocked."
                setTextColor(Color.DKGRAY)
                setPadding(0, 4, 0, 24)
            })
            addView(sectionTitle("STATUS"))
            addView(status)
            addView(armed)
            addView(mirror)
            addView(stop)
            addView(sectionTitle("ACTION INPUT URL"))
            addView(endpointSource)
            addView(endpoint)
            addView(deviceId)
            addView(deviceToken)
            addView(publicKey)
            addView(sectionTitle("SCREEN MIRROR OUTPUT URL"))
            addView(mirrorEndpoint)
            addView(mirrorToken)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(mirrorInterval, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(mirrorQuality, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(mirrorWidth)
            addView(autoResume)
            addView(saveApi)
            addView(sectionTitle("ANDROID PERMISSION"))
            addView(accessibility)
            addView(sectionTitle("RECENT ACTIVITY"))
            addView(logs)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        refreshStatus()
        if (intent.getBooleanExtra(EXTRA_RESUME_MIRROR, false)) main.post { requestMirrorCapture() }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        refreshStatus()
        main.removeCallbacks(refreshRunnable)
        main.post(refreshRunnable)
    }

    override fun onPause() {
        activityResumed = false
        main.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    @Deprecated("MediaProjection still returns its consent result through Activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            syncMirrorSwitch(false)
            toast("Screen sharing was not started")
            return
        }
        val service = Intent(this, MirrorService::class.java)
            .setAction(MirrorService.ACTION_START)
            .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MirrorService.EXTRA_RESULT_DATA, data)
            .putExtra(MirrorService.EXTRA_ENDPOINT, store.mirrorEndpoint)
            .putExtra(MirrorService.EXTRA_TOKEN, store.mirrorToken.ifBlank { store.deviceToken })
            .putExtra(MirrorService.EXTRA_DEVICE_ID, store.deviceId)
            .putExtra(MirrorService.EXTRA_INTERVAL_MS, store.mirrorIntervalMs.toLong())
            .putExtra(MirrorService.EXTRA_JPEG_QUALITY, store.mirrorJpegQuality)
            .putExtra(MirrorService.EXTRA_MAX_WIDTH, store.mirrorMaxWidth)
        startForegroundService(service)
        syncMirrorSwitch(true)
    }

    private fun requestMirrorCapture() {
        if (!CommandApi.isAllowedEndpoint(store.mirrorEndpoint)) {
            syncMirrorSwitch(false)
            toast("Save valid screen mirror settings first")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            syncMirrorSwitch(false)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            toast("Allow notifications, then enable screen mirroring again")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    private fun syncMirrorSwitch(value: Boolean) {
        syncingMirrorSwitch = true
        mirror.isChecked = value
        syncingMirrorSwitch = false
    }

    private fun settingsComplete(): Boolean = store.endpoint.isNotBlank() && store.publicKeyBase64.isNotBlank() &&
        (store.directEndpoint || (store.deviceId.isNotBlank() && store.deviceToken.isNotBlank()))

    private fun refreshStatus() {
        status.text = buildString {
            append("Accessibility: ")
            append(if (LogiclickAccessibilityService.instance == null) "disabled" else "enabled")
            append("\nRemote control: ")
            append(if (store.armed) "ACTIVE" else "stopped")
            append("\nScreen mirror: ")
            append(if (MirrorService.isRunning) "SHARING" else "stopped")
            append("\nDevice ID: ${store.deviceId}")
            append("\nSource mode: ${if (store.directEndpoint) "custom direct URL" else "Logiclick Raspberry Pi"}")
            append("\nAPI: ")
            append(if (store.endpoint.isBlank()) "not configured" else store.endpoint)
        }
        armed.isChecked = store.armed
        if (::mirror.isInitialized && mirror.isChecked != MirrorService.isRunning) syncMirrorSwitch(MirrorService.isRunning)
        logs.text = store.recentLogs().ifEmpty { listOf("No activity yet") }.joinToString("\n")
    }

    private fun field(hintText: String, value: String, variation: Int = InputType.TYPE_TEXT_VARIATION_NORMAL) =
        EditText(this).apply {
            hint = hintText
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or variation
            setSingleLine(true)
        }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF215CFF.toInt())
        gravity = Gravity.START
        setPadding(0, 28, 0, 8)
    }

    companion object {
        const val EXTRA_RESUME_MIRROR = "resumeMirror"
        private const val REQUEST_NOTIFICATIONS = 701
        private const val REQUEST_SCREEN_CAPTURE = 702
    }
}
