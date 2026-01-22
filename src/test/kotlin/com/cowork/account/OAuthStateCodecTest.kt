package com.cowork.account

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.oauth.OAuthStateCodec
import com.cowork.global.security.JwtProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class OAuthStateCodecTest {

    private val properties = JwtProperties(secret = "test-secret-key-that-is-at-least-32-bytes-long")
    private val issuedAt = Instant.parse("2026-02-01T00:00:00Z")

    @Test
    fun `state를 인코딩한 뒤 디코딩하면 같은 값을 얻는다`() {
        val codec = OAuthStateCodec(properties, Clock.fixed(issuedAt, ZoneId.of("UTC")))
        val state = OAuthStateCodec.OAuthState(channelId = 3, userId = 7, provider = AccountProvider.GITHUB)

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun `만료된 state는 거부한다`() {
        val encoder = OAuthStateCodec(properties, Clock.fixed(issuedAt, ZoneId.of("UTC")))
        val decoder = OAuthStateCodec(properties, Clock.fixed(issuedAt.plus(Duration.ofMinutes(11)), ZoneId.of("UTC")))
        val token = encoder.encode(OAuthStateCodec.OAuthState(3, 7, AccountProvider.GOOGLE))

        assertNull(decoder.decode(token))
    }

    @Test
    fun `변조된 state는 거부한다`() {
        val codec = OAuthStateCodec(properties, Clock.fixed(issuedAt, ZoneId.of("UTC")))
        val token = codec.encode(OAuthStateCodec.OAuthState(3, 7, AccountProvider.GITHUB))

        assertNull(codec.decode(token.dropLast(2) + "xx"))
    }
}
