package com.cowork.channel.account.oauth

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.global.security.JwtProperties
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.Date

/**
 * OAuth state 값을 서명된 JWT로 만들어 위조와 재사용 시간을 제한한다.
 */
@Component
class OAuthStateCodec(
    jwtProperties: JwtProperties,
    private val clock: Clock,
) {
    private val key = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    fun encode(state: OAuthState): String {
        val now = clock.millis()
        return Jwts.builder()
            .subject(state.userId.toString())
            .claim(PURPOSE_CLAIM, PURPOSE)
            .claim(CHANNEL_CLAIM, state.channelId)
            .claim(PROVIDER_CLAIM, state.provider.name)
            .issuedAt(Date(now))
            .expiration(Date(now + TTL.toMillis()))
            .signWith(key)
            .compact()
    }

    fun decode(token: String): OAuthState? = try {
        val claims = Jwts.parser().verifyWith(key).clock { Date(clock.millis()) }.build().parseSignedClaims(token).payload
        if (claims[PURPOSE_CLAIM] != PURPOSE) {
            null
        } else {
            OAuthState(
                channelId = (claims[CHANNEL_CLAIM] as Number).toLong(),
                userId = claims.subject.toLong(),
                provider = AccountProvider.valueOf(claims[PROVIDER_CLAIM] as String),
            )
        }
    } catch (e: JwtException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    data class OAuthState(
        val channelId: Long,
        val userId: Long,
        val provider: AccountProvider,
    )

    companion object {
        private val TTL: Duration = Duration.ofMinutes(10)
        private const val PURPOSE_CLAIM = "purpose"
        private const val PURPOSE = "account-oauth"
        private const val CHANNEL_CLAIM = "channelId"
        private const val PROVIDER_CLAIM = "provider"
    }
}
