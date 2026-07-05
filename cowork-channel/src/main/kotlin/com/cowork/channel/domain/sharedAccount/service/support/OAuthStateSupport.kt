package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class OAuthStateSupport(
    private val oAuthProperties: OAuthProperties,
    private val objectMapper: ObjectMapper,
) {

    init {
        require(oAuthProperties.stateSecret.isNotBlank()) { "account-share.oauth.state-secret must not be empty" }
    }

    fun providerConfigOf(provider: AccountProvider): OAuthProviderConfig = when (provider) {
        AccountProvider.GITHUB -> oAuthProperties.github
        AccountProvider.NOTION -> oAuthProperties.notion
        AccountProvider.JIRA -> oAuthProperties.jira
        AccountProvider.GOOGLE -> oAuthProperties.google
        AccountProvider.FACEBOOK -> oAuthProperties.facebook
        else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다.", HttpStatus.BAD_REQUEST)
    }

    // state = base64url(json_payload).base64url(hmac-sha256)
    // payload: { channelId, userId, provider, nonce, exp }
    fun buildState(channelId: Long, userId: Long, provider: AccountProvider): String {
        val payload = mapOf(
            "channelId" to channelId,
            "userId" to userId,
            "provider" to provider.name,
            "nonce" to UUID.randomUUID().toString(),
            "exp" to (Instant.now().epochSecond + 300),
        )
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payloadJson = objectMapper.writeValueAsString(payload)
        val payloadB64 = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val signature = hmacSign(payloadB64)
        return "$payloadB64.$signature"
    }

    fun verifyState(state: String, provider: AccountProvider): Pair<Long, Long> {
        val parts = state.split(".")
        if (parts.size != 2) throw ExpectedException("유효하지 않은 state입니다.", HttpStatus.BAD_REQUEST)

        val (payloadB64, signature) = parts
        if (hmacSign(payloadB64) != signature) {
            throw ExpectedException("state 서명 검증 실패.", HttpStatus.BAD_REQUEST)
        }

        val decoder = Base64.getUrlDecoder()
        val payloadJson = String(decoder.decode(payloadB64), Charsets.UTF_8)

        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any>

        val exp = (payload["exp"] as? Number)?.toLong()
            ?: throw ExpectedException("state payload 오류.", HttpStatus.BAD_REQUEST)
        if (Instant.now().epochSecond > exp) {
            throw ExpectedException("state가 만료되었습니다.", HttpStatus.BAD_REQUEST)
        }
        if (payload["provider"] != provider.name) {
            throw ExpectedException("state provider 불일치.", HttpStatus.BAD_REQUEST)
        }

        val channelId = (payload["channelId"] as? Number)?.toLong()
            ?: throw ExpectedException("state channelId 오류.", HttpStatus.BAD_REQUEST)
        val userId = (payload["userId"] as? Number)?.toLong()
            ?: throw ExpectedException("state userId 오류.", HttpStatus.BAD_REQUEST)

        return channelId to userId
    }

    private fun hmacSign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(oAuthProperties.stateSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }
}
