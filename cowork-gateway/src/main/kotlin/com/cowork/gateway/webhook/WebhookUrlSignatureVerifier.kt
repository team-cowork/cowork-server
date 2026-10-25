package com.cowork.gateway.webhook

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookUrlSignatureVerifier(encodedKey: String?) {
    private val key = encodedKey?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        ?.takeIf { it.size == 32 }
        ?.let { SecretKeySpec(it, "HmacSHA256") }

    fun verify(executionId: String?, signature: String?): Verification {
        val canonicalId = canonicalExecutionId(executionId) ?: return Verification.INVALID
        if (signature == null || signature.length != 64 || !signature.all { it in '0'..'9' || it in 'a'..'f' }) {
            return Verification.INVALID
        }
        val signingKey = key ?: return Verification.UNAVAILABLE
        val mac = Mac.getInstance("HmacSHA256").apply { init(signingKey) }
        val expected = mac.doFinal("cowork:webhook:execute:v1:$canonicalId".toByteArray(StandardCharsets.UTF_8))
        return if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            Verification.VALID
        } else {
            Verification.INVALID
        }
    }

    enum class Verification { VALID, INVALID, UNAVAILABLE }

    companion object {
        private val executionIdPattern =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

        fun canonicalExecutionId(value: String?): String? = value
            ?.takeIf { it.length == 36 && executionIdPattern.matches(it) }
            ?.lowercase(Locale.ROOT)
    }
}
