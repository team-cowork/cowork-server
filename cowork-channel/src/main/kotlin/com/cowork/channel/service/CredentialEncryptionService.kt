package com.cowork.channel.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class CredentialEncryptionService(
    @Value("\${account-share.encryption-key}") base64Key: String,
) {
    private val secretKey: SecretKey

    init {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        require(keyBytes.size == 32) { "account-share.encryption-key must be 256-bit (32 bytes base64)" }
        secretKey = SecretKeySpec(keyBytes, "AES")
    }

    // 저장 형식: base64(12-byte IV):base64(ciphertext + 16-byte GCM auth tag)
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
    }

    fun decrypt(encrypted: String): String {
        val parts = encrypted.split(":")
        require(parts.size == 2) { "Invalid encrypted credential format" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0])
        val ciphertext = decoder.decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    // "••••1234" 형식 (마지막 4자리만 노출, 전체가 4자 이하면 전부 마스킹)
    fun mask(plaintext: String): String =
        if (plaintext.length <= 4) "••••" else "••••" + plaintext.takeLast(4)
}
