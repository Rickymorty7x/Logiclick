package com.logiclick.app.model

import org.json.JSONObject

data class ApiCommand(
    val commandId: String,
    val workflowName: String,
    val number: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val nonce: String,
    val signatureBase64: String
) {
    /** Exact UTF-8 bytes signed by the API's Ed25519 private key. */
    fun signingPayload(): ByteArray = listOf(
        commandId,
        workflowName,
        number,
        issuedAtEpochMs.toString(),
        expiresAtEpochMs.toString(),
        nonce
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun basicValidation(now: Long = System.currentTimeMillis()): String? = when {
        commandId.isBlank() || nonce.length < 12 -> "Invalid command identity"
        !number.matches(Regex("^[0-9]{1,18}$")) -> "Number must contain 1–18 digits"
        issuedAtEpochMs > now + 30_000 -> "Command timestamp is in the future"
        expiresAtEpochMs <= now -> "Command expired"
        expiresAtEpochMs - issuedAtEpochMs !in 1..120_000 -> "Command lifetime must be at most 2 minutes"
        else -> null
    }

    companion object {
        fun fromJson(json: JSONObject) = ApiCommand(
            commandId = json.getString("commandId"),
            workflowName = json.getString("workflowName"),
            number = json.getString("number"),
            issuedAtEpochMs = json.getLong("issuedAtEpochMs"),
            expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
            nonce = json.getString("nonce"),
            signatureBase64 = json.getString("signature")
        )
    }
}
