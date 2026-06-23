package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class CredentialEncryptionServiceTest {

    private val testKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val service = CredentialEncryptionService(testKey)

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
    fun `mask는 4자 이하 문자열을 전체 마스킹함`() {
        assertEquals("••••", service.mask("abc"))
        assertEquals("••••", service.mask("1234"))
    }

    @Test
    fun `mask는 5자 이상이면 ••••로 시작하고 마지막 자리를 노출함`() {
        val result = service.mask("password123")
        assertTrue(result.startsWith("••••"))
        assertTrue(result.length > 4)
        assertTrue("password123".endsWith(result.removePrefix("••••")))
    }

    @Test
    fun `decrypt는 콜론이 없는 잘못된 형식이면 예외가 발생함`() {
        assertThrows<IllegalArgumentException> {
            service.decrypt("invalidformatnocolon")
        }
    }
}
