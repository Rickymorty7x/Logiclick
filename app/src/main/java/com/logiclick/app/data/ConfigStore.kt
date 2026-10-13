package com.logiclick.app.data

import android.content.Context
import com.logiclick.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("logiclick", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", BuildConfig.DEFAULT_API_URL) ?: BuildConfig.DEFAULT_API_URL
        set(value) = prefs.edit().putString("endpoint", value.trim()).apply()

    var customEndpoint: String
        get() = prefs.getString("customEndpoint", "") ?: ""
        set(value) = prefs.edit().putString("customEndpoint", value.trim()).apply()

    var directEndpoint: Boolean
        get() = prefs.getBoolean("directEndpoint", false)
        set(value) = prefs.edit().putBoolean("directEndpoint", value).apply()

    var mirrorEndpoint: String
        get() = prefs.getString(
            "mirrorEndpoint",
            "${BuildConfig.DEFAULT_API_URL}/v1/devices/$deviceId/mirror/frame"
        ) ?: "${BuildConfig.DEFAULT_API_URL}/v1/devices/$deviceId/mirror/frame"
        set(value) = prefs.edit().putString("mirrorEndpoint", value.trim()).apply()

    var mirrorToken: String
        get() = prefs.getString("mirrorToken", "") ?: ""
        set(value) = prefs.edit().putString("mirrorToken", value.trim()).apply()

    var mirrorIntervalMs: Int
        get() = prefs.getInt("mirrorIntervalMs", 1_000)
        set(value) = prefs.edit().putInt("mirrorIntervalMs", value).apply()

    var mirrorJpegQuality: Int
        get() = prefs.getInt("mirrorJpegQuality", 60)
        set(value) = prefs.edit().putInt("mirrorJpegQuality", value).apply()

    var mirrorMaxWidth: Int
        get() = prefs.getInt("mirrorMaxWidth", 720)
        set(value) = prefs.edit().putInt("mirrorMaxWidth", value).apply()

    var mirrorAutoResume: Boolean
        get() = prefs.getBoolean("mirrorAutoResume", true)
        set(value) = prefs.edit().putBoolean("mirrorAutoResume", value).apply()

    var publicKeyBase64: String
        get() = prefs.getString("publicKey", BuildConfig.DEFAULT_PUBLIC_KEY) ?: BuildConfig.DEFAULT_PUBLIC_KEY
        set(value) = prefs.edit().putString("publicKey", value.trim()).apply()

    var deviceId: String
        get() = prefs.getString("deviceId", "phone-1") ?: "phone-1"
        set(value) = prefs.edit().putString("deviceId", value.trim()).apply()

    var deviceToken: String
        get() = prefs.getString("deviceToken", "") ?: ""
        set(value) = prefs.edit().putString("deviceToken", value.trim()).apply()

    var armed: Boolean
        get() = prefs.getBoolean("armed", false)
        set(value) = prefs.edit().putBoolean("armed", value).apply()

    fun hasSeen(commandId: String): Boolean = prefs.getStringSet("seen", emptySet())!!.contains(commandId)

    fun markSeen(commandId: String) {
        val updated = prefs.getStringSet("seen", emptySet())!!.toMutableList()
        updated.remove(commandId)
        updated.add(commandId)
        prefs.edit().putStringSet("seen", updated.takeLast(100).toSet()).apply()
    }

    @Synchronized
    fun appendLog(message: String) {
        val existing = runCatching { JSONArray(prefs.getString("logs", "[]")) }.getOrElse { JSONArray() }
        val entries = mutableListOf<JSONObject>()
        for (index in 0 until existing.length()) {
            existing.optJSONObject(index)?.let(entries::add)
        }
        entries.add(JSONObject().put("at", System.currentTimeMillis()).put("message", message.take(180)))
        val trimmed = JSONArray()
        entries.takeLast(30).forEach(trimmed::put)
        prefs.edit().putString("logs", trimmed.toString()).apply()
    }

    fun recentLogs(limit: Int = 8): List<String> {
        val array = runCatching { JSONArray(prefs.getString("logs", "[]")) }.getOrElse { JSONArray() }
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return (maxOf(0, array.length() - limit) until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { entry ->
                "${formatter.format(Date(entry.optLong("at")))}  ${entry.optString("message")}"
            }
        }.reversed()
    }
}
