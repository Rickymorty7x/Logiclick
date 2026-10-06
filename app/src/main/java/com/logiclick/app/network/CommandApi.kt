package com.logiclick.app.network

import com.logiclick.app.model.ApiCommand
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CommandApi {
    fun fetch(endpoint: String): Result<ApiCommand?> = runCatching {
        require(endpoint.startsWith("https://")) { "Only HTTPS endpoints are accepted" }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/json")
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
}
