package com.cowork.global.crypto

import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM으로 공유 계정 자격 증명을 암호화한다.
 * 저장 형식: Base64(IV(12바이트) + 암호문 + 인증 태그)
 */
@Component
class CredentialEncryptor(
    properties: CryptoProperties,
) {
    private val key: SecretKeySpec
    private val secureRandom = SecureRandom()

    init {
        val keyBytes = Base64.getDecoder().decode(properties.credentialKey)
        require(keyBytes.size == KEY_SIZE_BYTES) { "Credential key must be 32 bytes" }
        key = SecretKeySpec(keyBytes, ALGORITHM)
    }

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }

    fun decrypt(cipherText: String): String {
        val bytes = Base64.getDecoder().decode(cipherText)
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(bytes, IV_SIZE_BYTES, bytes.size - IV_SIZE_BYTES), Charsets.UTF_8)
    }

    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BYTES = 32
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
    }
}
