package com.cowork.auth

import com.cowork.auth.service.TokenHasher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TokenHasherTest {

    @Test
    fun `생성한 토큰은 매번 다르고 URL에 안전한 문자만 쓴다`() {
        val first = TokenHasher.generate()
        val second = TokenHasher.generate()

        assertNotEquals(first, second)
        assertEquals(43, first.length)
        assertEquals(true, first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `같은 토큰은 같은 SHA-256 해시를 만든다`() {
        val hash = TokenHasher.hash("token")

        assertEquals(hash, TokenHasher.hash("token"))
        assertEquals(64, hash.length)
        assertEquals("3c469e9d6c5875d37a43f353d4f88e61fcf812c66eee3457465a40b0da4153e0", hash)
    }
}
