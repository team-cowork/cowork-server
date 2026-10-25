package com.cowork.preference.service

import com.cowork.preference.domain.WebhookRequestException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The replay key is supplied by Config/Vault; a missing key disables secret-issuing mutations. */
class WebhookSecrets(encodedKey: String?) {
    private val random = SecureRandom()
    private val key = encodedKey?.takeIf(String::isNotBlank)?.let {
        val bytes = runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        require(bytes?.size == 32) { "Webhook secret encryption key must be 32 base64-encoded bytes" }
        SecretKeySpec(bytes, "AES")
    }

    fun generate(): String {
        requireKey()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    }

    fun encrypt(token: String, context: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decrypt(ciphertext: String, context: String): String {
        val data = Base64.getDecoder().decode(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), StandardCharsets.UTF_8)
    }

    private fun requireKey(): SecretKeySpec = key ?: throw WebhookRequestException(503, "웹훅 비밀키 발급을 준비하고 있습니다.")

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
