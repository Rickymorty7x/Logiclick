package com.logiclick.app.data

import android.content.Context
import com.logiclick.app.model.WorkflowConfig
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("logiclick", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) = prefs.edit().putString("endpoint", value.trim()).apply()

    var publicKeyBase64: String
        get() = prefs.getString("publicKey", "") ?: ""
        set(value) = prefs.edit().putString("publicKey", value.trim()).apply()

    var armed: Boolean
        get() = prefs.getBoolean("armed", false)
        set(value) = prefs.edit().putBoolean("armed", value).apply()

    var workflow: WorkflowConfig?
        get() = prefs.getString("workflow", null)?.let {
            runCatching { WorkflowConfig.fromJson(JSONObject(it)) }.getOrNull()
        }
        set(value) = prefs.edit().apply {
            if (value == null) remove("workflow") else putString("workflow", value.toJson().toString())
        }.apply()

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
