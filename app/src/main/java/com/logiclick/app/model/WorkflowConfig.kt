package com.logiclick.app.model

import android.graphics.RectF
import org.json.JSONObject

data class NormalizedPoint(val x: Float, val y: Float) {
    fun isValid() = x in 0f..1f && y in 0f..1f
    fun toJson() = JSONObject().put("x", x).put("y", y)

    companion object {
        fun fromJson(json: JSONObject) = NormalizedPoint(
            json.getDouble("x").toFloat(),
            json.getDouble("y").toFloat()
        )
    }
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun isValid() = left in 0f..1f && top in 0f..1f &&
        right in 0f..1f && bottom in 0f..1f && right > left && bottom > top

    fun toPixels(width: Int, height: Int) = RectF(
        left * width, top * height, right * width, bottom * height
    )

    fun toJson() = JSONObject()
        .put("left", left).put("top", top)
        .put("right", right).put("bottom", bottom)

    companion object {
        fun fromJson(json: JSONObject) = NormalizedRect(
            json.getDouble("left").toFloat(),
            json.getDouble("top").toFloat(),
            json.getDouble("right").toFloat(),
            json.getDouble("bottom").toFloat()
        )
    }
}

data class WorkflowConfig(
    val name: String,
    val targetPackage: String,
    val ocrRegion: NormalizedRect,
    val inputPoint: NormalizedPoint,
    val submitPoint: NormalizedPoint,
    val expectedTextRegex: String = ".+",
    val minDelayMs: Long = 450,
    val maxDelayMs: Long = 900
) {
    fun validate(): String? = when {
        name.isBlank() -> "Workflow name is required"
        targetPackage.isBlank() -> "Target package is required"
        !ocrRegion.isValid() -> "OCR region is invalid"
        !inputPoint.isValid() || !submitPoint.isValid() -> "A click point is invalid"
        minDelayMs !in 250..5_000 -> "Minimum delay must be 250–5000 ms"
        maxDelayMs !in minDelayMs..10_000 -> "Maximum delay is invalid"
        runCatching { Regex(expectedTextRegex) }.isFailure -> "Expected text rule is invalid"
        else -> null
    }

    fun toJson() = JSONObject()
        .put("name", name)
        .put("targetPackage", targetPackage)
        .put("ocrRegion", ocrRegion.toJson())
        .put("inputPoint", inputPoint.toJson())
        .put("submitPoint", submitPoint.toJson())
        .put("expectedTextRegex", expectedTextRegex)
        .put("minDelayMs", minDelayMs)
        .put("maxDelayMs", maxDelayMs)

    companion object {
        fun fromJson(json: JSONObject) = WorkflowConfig(
            name = json.getString("name"),
            targetPackage = json.getString("targetPackage"),
            ocrRegion = NormalizedRect.fromJson(json.getJSONObject("ocrRegion")),
            inputPoint = NormalizedPoint.fromJson(json.getJSONObject("inputPoint")),
            submitPoint = NormalizedPoint.fromJson(json.getJSONObject("submitPoint")),
            expectedTextRegex = json.optString("expectedTextRegex", ".+"),
            minDelayMs = json.optLong("minDelayMs", 450),
            maxDelayMs = json.optLong("maxDelayMs", 900)
        )
    }
}
