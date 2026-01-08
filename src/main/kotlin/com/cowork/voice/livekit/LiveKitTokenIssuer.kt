package com.cowork.voice.livekit

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Date

@Component
class LiveKitTokenIssuer(
    private val properties: LiveKitProperties,
    private val clock: Clock,
) {
    private val key = Keys.hmacShaKeyFor(properties.apiSecret.toByteArray())

    fun issue(roomName: String, identity: String, displayName: String): String {
        val now = clock.millis()
        return Jwts.builder()
            .issuer(properties.apiKey)
            .subject(identity)
            .claim("name", displayName)
            .claim(
                "video",
                mapOf(
                    "room" to roomName,
                    "roomJoin" to true,
                    "canPublish" to true,
                    "canSubscribe" to true,
                    "canPublishData" to true,
                ),
            )
            .notBefore(Date(now))
            .expiration(Date(now + properties.tokenTtl.toMillis()))
            .signWith(key)
            .compact()
    }
}
