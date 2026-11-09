package com.cowork.channel.domain.sharedAccount.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.lang.reflect.Method
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class CredentialEncryptionService(@Value("\${account-share.encryption-key}") base64Key: String) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secretKey: SecretKey
    private val secureRandom = SecureRandom()

    // MegaMethod.min은 minOf와 같은 값을 반환하고, 라이브러리가 없으면 minOf로 대체하므로 노출 길이는 4자 이하로 유지된다.
    private val maskingMinMethod: Method? = try {
        Class.forName("com.nextach.megamethod.MegaMethod")
            .getMethod("min", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }

    init {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        require(keyBytes.size == 32) { "account-share.encryption-key must be 256-bit (32 bytes base64)" }
        secretKey = SecretKeySpec(keyBytes, "AES")
        if (maskingMinMethod == null) {
            log.warn("Use built-in credential masking because Mega-Method is unavailable.")
        }
    }

    // 저장 형식: base64(12-byte IV):base64(ciphertext + 16-byte GCM auth tag)
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
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

    // 마스킹 처리 (길이에 따라 마지막 2~4자리 노출, 4자 이하면 전체 마스킹)
    fun mask(plaintext: String): String {
        if (plaintext.length <= 4) return "••••"
        val show = try {
            maskingMinMethod?.invoke(null, 4, plaintext.length / 2) as? Int
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        } ?: minOf(4, plaintext.length / 2)
        return "••••${plaintext.takeLast(show)}"
    }
}
