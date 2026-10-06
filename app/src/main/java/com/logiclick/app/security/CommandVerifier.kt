package com.logiclick.app.security

import android.util.Base64
import com.logiclick.app.model.ApiCommand
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object CommandVerifier {
    fun verify(command: ApiCommand, publicKeyBase64: String): Boolean = runCatching {
        val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(command.signingPayload())
        verifier.verify(Base64.decode(command.signatureBase64, Base64.DEFAULT))
    }.getOrDefault(false)
}
