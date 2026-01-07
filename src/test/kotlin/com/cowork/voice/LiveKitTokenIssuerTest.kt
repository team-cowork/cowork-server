package com.cowork.voice

import com.cowork.voice.livekit.LiveKitProperties
import com.cowork.voice.livekit.LiveKitTokenIssuer
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class LiveKitTokenIssuerTest {

    private val secret = "livekit-api-secret-that-is-long-enough"
    private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
    private val issuer = LiveKitTokenIssuer(
        LiveKitProperties(url = "ws://localhost:7880", apiKey = "devkey", apiSecret = secret, tokenTtl = Duration.ofHours(1)),
        clock,
    )

    private fun parse(token: String) =
        Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.toByteArray())).build().parseSignedClaims(token).payload

    @Test
    fun `토큰에 API 키와 참여자 정보를 담는다`() {
        val claims = parse(issuer.issue("channel-3-room", "7", "홍길동"))

        assertEquals("devkey", claims.issuer)
        assertEquals("7", claims.subject)
        assertEquals("홍길동", claims["name"])
    }

    @Test
    fun `토큰은 지정한 방에만 입장할 수 있다`() {
        @Suppress("UNCHECKED_CAST")
        val video = parse(issuer.issue("channel-3-room", "7", "홍길동"))["video"] as Map<String, Any>

        assertEquals("channel-3-room", video["room"])
        assertEquals(true, video["roomJoin"])
    }
}
