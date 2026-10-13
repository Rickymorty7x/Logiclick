package com.logiclick.app.model

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class CoordinateArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left <= right && top <= bottom)
    }
}

data class TimingRange(val min: Long, val max: Long) {
    init { require(min in 1..60_000 && max in min..60_000) }
    fun random(): Long = if (min == max) min else kotlin.random.Random.nextLong(min, max + 1)
}

data class GestureStroke(
    val points: List<CoordinateArea>,
    val startDelayMs: Long,
    val durationMs: TimingRange
) {
    init {
        require(points.isNotEmpty() && points.size <= 64)
        require(startDelayMs in 0..60_000)
    }
}

sealed interface RemoteAction {
    data class Gesture(val coordinateSpace: String, val strokes: List<GestureStroke>) : RemoteAction
    data class Global(val name: String) : RemoteAction
    data class LaunchApp(val packageName: String) : RemoteAction
}

data class ApiCommand(
    val commandId: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val nonce: String,
    val payloadBase64: String,
    val signatureBase64: String,
    val action: RemoteAction
) {
    /** Exact UTF-8 bytes signed by the Raspberry Pi. */
    fun signingPayload(): ByteArray = listOf(
        commandId,
        issuedAtEpochMs.toString(),
        expiresAtEpochMs.toString(),
        nonce,
        payloadBase64
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun basicValidation(now: Long = System.currentTimeMillis()): String? = when {
        !commandId.matches(Regex("^[A-Za-z0-9_-]{8,80}$")) || nonce.length !in 12..120 -> "Invalid command identity"
        issuedAtEpochMs > now + 30_000 -> "Command timestamp is in the future"
        expiresAtEpochMs <= now -> "Command expired"
        expiresAtEpochMs - issuedAtEpochMs !in 1..120_000 -> "Command lifetime must be at most 2 minutes"
        payloadBase64.length > 32_000 -> "Command payload is too large"
        else -> null
    }

    companion object {
        fun fromJson(json: JSONObject): ApiCommand {
            val payloadBase64 = json.getString("payload")
            val payloadBytes = Base64.decode(payloadBase64, Base64.DEFAULT)
            require(payloadBytes.size <= 24_000) { "Payload is too large" }
            val payload = JSONObject(payloadBytes.toString(Charsets.UTF_8))
            return ApiCommand(
                commandId = json.getString("commandId"),
                issuedAtEpochMs = json.getLong("issuedAtEpochMs"),
                expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
                nonce = json.getString("nonce"),
                payloadBase64 = payloadBase64,
                signatureBase64 = json.getString("signature"),
                action = parseAction(payload)
            )
        }

        private fun parseAction(payload: JSONObject): RemoteAction = when (val type = payload.getString("type")) {
            "tap" -> RemoteAction.Gesture(
                payload.optString("coordinateSpace", "normalized"),
                listOf(GestureStroke(listOf(area(payload, "area")), 0, timing(payload, "holdMs", 60, 100)))
            )
            "double_tap" -> {
                val hold = timing(payload, "holdMs", 50, 90)
                val gap = timing(payload, "gapMs", 90, 160)
                val target = area(payload, "area")
                RemoteAction.Gesture(
                    payload.optString("coordinateSpace", "normalized"),
                    listOf(
                        GestureStroke(listOf(target), 0, hold),
                        GestureStroke(listOf(target), hold.max + gap.random(), hold)
                    )
                )
            }
            "long_press" -> RemoteAction.Gesture(
                payload.optString("coordinateSpace", "normalized"),
                listOf(GestureStroke(listOf(area(payload, "area")), 0, timing(payload, "holdMs", 500, 900)))
            )
            "swipe", "drag", "scroll", "fling" -> RemoteAction.Gesture(
                payload.optString("coordinateSpace", "normalized"),
                listOf(GestureStroke(
                    listOf(area(payload, "startArea"), area(payload, "endArea")),
                    0,
                    timing(payload, "durationMs", 250, 450)
                ))
            )
            "gesture" -> {
                val strokesJson = payload.getJSONArray("strokes")
                require(strokesJson.length() in 1..10) { "A gesture needs 1–10 strokes" }
                val strokes = (0 until strokesJson.length()).map { index ->
                    val stroke = strokesJson.getJSONObject(index)
                    val pointsJson = stroke.getJSONArray("points")
                    GestureStroke(
                        points = parseAreas(pointsJson),
                        startDelayMs = stroke.optLong("startDelayMs", 0),
                        durationMs = timing(stroke, "durationMs", 250, 450)
                    )
                }
                RemoteAction.Gesture(payload.optString("coordinateSpace", "normalized"), strokes)
            }
            "global_action" -> RemoteAction.Global(payload.getString("action"))
            "launch_app" -> RemoteAction.LaunchApp(payload.getString("packageName"))
            else -> error("Unsupported action type: $type")
        }

        private fun parseAreas(array: JSONArray): List<CoordinateArea> {
            require(array.length() in 1..64) { "A stroke needs 1–64 points" }
            return (0 until array.length()).map { parseArea(array.getJSONObject(it)) }
        }

        private fun area(parent: JSONObject, key: String): CoordinateArea = parseArea(parent.getJSONObject(key))

        private fun parseArea(json: JSONObject) = CoordinateArea(
            left = json.getDouble("left").toFloat(),
            top = json.getDouble("top").toFloat(),
            right = json.getDouble("right").toFloat(),
            bottom = json.getDouble("bottom").toFloat()
        )

        private fun timing(parent: JSONObject, key: String, defaultMin: Long, defaultMax: Long): TimingRange {
            val value = parent.optJSONObject(key) ?: return TimingRange(defaultMin, defaultMax)
            return TimingRange(value.getLong("min"), value.getLong("max"))
        }
    }
}
