package com.cowork.voice

import com.cowork.global.error.BusinessException
import com.cowork.voice.livekit.LiveKitProperties
import com.cowork.voice.livekit.LiveKitWebhookVerifier
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.Base64
import java.util.Date

class LiveKitWebhookVerifierTest {

    private val properties = LiveKitProperties(url = "ws://localhost:7880", apiKey = "devkey", apiSecret = "test-livekit-secret-that-is-long-enough")
    private val verifier = LiveKitWebhookVerifier(properties)

    private fun sign(body: String, secret: String = properties.apiSecret, issuer: String = properties.apiKey): String {
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        return Jwts.builder()
            .issuer(issuer)
            .claim("sha256", hash)
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()))
            .compact()
    }

    @Test
    fun `올바르게 서명된 웹훅을 통과시킨다`() {
        val body = """{"event":"room_finished","room":{"name":"channel-1"}}"""

        assertDoesNotThrow { verifier.verify(sign(body), body) }
    }

    @Test
    fun `본문이 바뀌면 거부한다`() {
        val token = sign("""{"event":"room_finished"}""")

        assertThrows<BusinessException> { verifier.verify(token, """{"event":"participant_left"}""") }
    }

    @Test
    fun `다른 비밀키로 서명한 웹훅은 거부한다`() {
        val body = "{}"

        assertThrows<BusinessException> { verifier.verify(sign(body, secret = "another-secret-that-is-long-enough-too"), body) }
    }

    @Test
    fun `인증 헤더가 없으면 거부한다`() {
        assertThrows<BusinessException> { verifier.verify(null, "{}") }
    }
}
