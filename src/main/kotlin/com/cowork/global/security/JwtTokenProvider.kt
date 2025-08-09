package com.cowork.global.security

import com.cowork.user.domain.UserRole
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    private val properties: JwtProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray())

    fun createAccessToken(userId: Long, role: UserRole): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .claim(ROLE_CLAIM, role.name)
            .claim(TYPE_CLAIM, ACCESS_TYPE)
            .issuedAt(now)
            .expiration(Date(now.time + properties.accessTokenTtl.toMillis()))
            .signWith(key)
            .compact()
    }

    fun parseAccessToken(token: String): AuthUser? = try {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        if (claims[TYPE_CLAIM] != ACCESS_TYPE) {
            null
        } else {
            AuthUser(
                id = claims.subject.toLong(),
                role = UserRole.valueOf(claims[ROLE_CLAIM] as String),
            )
        }
    } catch (e: JwtException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    val accessTokenTtlSeconds: Long get() = properties.accessTokenTtl.seconds

    companion object {
        private const val ROLE_CLAIM = "role"
        private const val TYPE_CLAIM = "typ"
        private const val ACCESS_TYPE = "access"
    }
}
