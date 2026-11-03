package com.cowork.channel.domain.sharedAccount.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class CredentialEncryptionServiceTest {

    private val testKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val service = CredentialEncryptionService(testKey)

    @Nested
    inner class Encryption {

        @Test
        fun `encrypt와 decrypt는 라운드트립이 일치함`() {
            val plain = "mySecretPassword123"
            assertEquals(plain, service.decrypt(service.encrypt(plain)))
        }

        @Test
        fun `encrypt는 매번 다른 IV를 사용하여 다른 암호문을 생성함`() {
            val plain = "same-password"
            assertNotEquals(service.encrypt(plain), service.encrypt(plain))
        }

        @Test
        fun `decrypt는 콜론이 없는 잘못된 형식이면 예외가 발생함`() {
            assertThrows<IllegalArgumentException> {
                service.decrypt("invalidformatnocolon")
            }
        }
    }

    @Nested
    inner class Masking {

        @Test
        fun `mask는 4자 이하 문자열을 전체 마스킹함`() {
            assertEquals("••••", service.mask(""))
            assertEquals("••••", service.mask("a"))
            assertEquals("••••", service.mask("abc"))
            assertEquals("••••", service.mask("1234"))
        }

        @Test
        fun `mask는 5자 비밀번호의 마지막 2자만 노출함`() {
            assertEquals("••••45", service.mask("12345"))
        }

        @Test
        fun `mask는 6자와 7자 비밀번호의 마지막 3자만 노출함`() {
            assertEquals("••••456", service.mask("123456"))
            assertEquals("••••567", service.mask("1234567"))
        }

        @Test
        fun `mask는 8자 이상 비밀번호의 마지막 4자만 노출함`() {
            assertEquals("••••5678", service.mask("12345678"))
            assertEquals("••••d123", service.mask("password123"))
        }
    }
}
