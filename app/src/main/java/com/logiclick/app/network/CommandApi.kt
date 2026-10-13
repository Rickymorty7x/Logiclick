package com.logiclick.app.network

import com.logiclick.app.model.ApiCommand
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URI

object CommandApi {
    fun fetch(
        endpoint: String,
        deviceId: String,
        deviceToken: String,
        directEndpoint: Boolean
    ): Result<ApiCommand?> = runCatching {
        require(isAllowedEndpoint(endpoint)) { "Use HTTPS or a Tailscale 100.64.0.0/10 HTTP address" }
        if (!directEndpoint) {
            require(deviceId.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) { "Invalid device ID" }
            require(deviceToken.length in 24..256) { "Invalid device token" }
        }
        require(deviceToken.length <= 512) { "Authentication token is too long" }
        val pollingUrl = if (directEndpoint) {
            endpoint
        } else {
            val base = endpoint.trimEnd('/')
            val encodedId = URLEncoder.encode(deviceId, Charsets.UTF_8.name())
            "$base/v1/devices/$encodedId/commands/next"
        }
        val connection = URL(pollingUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/json")
            if (deviceToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NO_CONTENT -> null
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use {
                    ApiCommand.fromJson(JSONObject(it.readText()))
                }
                else -> error("API returned HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun isAllowedEndpoint(endpoint: String): Boolean = runCatching {
        val uri = URI(endpoint)
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return@runCatching false
        if (uri.scheme == "https") return@runCatching true
        if (uri.scheme != "http") return@runCatching false
        val octets = uri.host.split('.').map(String::toInt)
        octets.size == 4 && octets[0] == 100 && octets[1] in 64..127 && octets.drop(2).all { it in 0..255 }
    }.getOrDefault(false)
}
