package com.cowork.crypto

import com.cowork.global.crypto.CredentialEncryptor
import com.cowork.global.crypto.CryptoProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class CredentialEncryptorTest {

    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val encryptor = CredentialEncryptor(CryptoProperties(key))

    @Test
    fun `암호화한 값을 복호화하면 원문과 같다`() {
        val encrypted = encryptor.encrypt("p@ssw0rd!")

        assertEquals("p@ssw0rd!", encryptor.decrypt(encrypted))
    }

    @Test
    fun `같은 원문도 매번 다른 암호문을 만든다`() {
        assertNotEquals(encryptor.encrypt("secret"), encryptor.encrypt("secret"))
    }

    @Test
    fun `변조된 암호문은 복호화할 수 없다`() {
        val bytes = Base64.getDecoder().decode(encryptor.encrypt("secret"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()

        assertThrows<Exception> { encryptor.decrypt(Base64.getEncoder().encodeToString(bytes)) }
    }

    @Test
    fun `32바이트가 아닌 키는 허용하지 않는다`() {
        assertThrows<IllegalArgumentException> {
            CredentialEncryptor(CryptoProperties(Base64.getEncoder().encodeToString(ByteArray(16))))
        }
    }
}
