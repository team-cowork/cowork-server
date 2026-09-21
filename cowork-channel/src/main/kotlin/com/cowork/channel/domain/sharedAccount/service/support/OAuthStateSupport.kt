package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class OAuthStateSupport(private val oAuthProperties: OAuthProperties, private val objectMapper: ObjectMapper) {

    init {
        require(oAuthProperties.stateSecret.isNotBlank()) { "account-share.oauth.state-secret must not be empty" }
        require(oAuthProperties.allowedReturnOrigins.isNotEmpty()) {
            "account-share.oauth.allowed-return-origins must not be empty"
        }
    }

    fun providerConfigOf(provider: AccountProvider): OAuthProviderConfig {
        val config = when (provider) {
            AccountProvider.GITHUB -> oAuthProperties.github
            AccountProvider.NOTION -> oAuthProperties.notion
            AccountProvider.JIRA -> oAuthProperties.jira
            AccountProvider.GOOGLE -> oAuthProperties.google
            AccountProvider.FACEBOOK -> oAuthProperties.facebook
            else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다.", HttpStatus.BAD_REQUEST)
        }
        if (config.clientId.isBlank() || config.clientSecret.isBlank()) {
            throw ExpectedException("OAuth 연동이 설정되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        return config
    }

    // state = base64url(json_payload).base64url(hmac-sha256)
    // payload: { channelId, userId, provider, returnOrigin, nonce, exp }
    fun buildState(channelId: Long, userId: Long, provider: AccountProvider, returnOrigin: String? = null): String {
        val payload = mapOf(
            "channelId" to channelId,
            "userId" to userId,
            "provider" to provider.name,
            "returnOrigin" to validateReturnOrigin(returnOrigin ?: oAuthProperties.allowedReturnOrigins.first()),
            "nonce" to UUID.randomUUID().toString(),
            "exp" to (Instant.now().epochSecond + 300),
        )
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payloadJson = objectMapper.writeValueAsString(payload)
        val payloadB64 = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val signature = hmacSign(payloadB64)
        return "$payloadB64.$signature"
    }

    fun verifyState(state: String, provider: AccountProvider): OAuthState {
        val parts = state.split(".")
        if (parts.size != 2) throw ExpectedException("유효하지 않은 state입니다.", HttpStatus.BAD_REQUEST)

        val (payloadB64, signature) = parts
        val expectedSignature = hmacSign(payloadB64).toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expectedSignature, signature.toByteArray(Charsets.UTF_8))) {
            throw ExpectedException("state 서명 검증에 실패했습니다.", HttpStatus.BAD_REQUEST)
        }

        val payload = runCatching {
            val payloadJson = String(Base64.getUrlDecoder().decode(payloadB64), Charsets.UTF_8)
            objectMapper.readValue(payloadJson, Map::class.java)
                ?: throw IllegalArgumentException("State payload must be an object")
        }.getOrElse {
            throw ExpectedException("state payload가 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        }

        val exp = (payload["exp"] as? Number)?.toLong()
            ?: throw ExpectedException("state payload가 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        if (Instant.now().epochSecond >= exp) {
            throw ExpectedException("state가 만료되었습니다.", HttpStatus.BAD_REQUEST)
        }
        if (payload["provider"] != provider.name) {
            throw ExpectedException("state의 provider가 일치하지 않습니다.", HttpStatus.BAD_REQUEST)
        }

        val channelId = (payload["channelId"] as? Number)?.toLong()
            ?: throw ExpectedException("state의 channelId 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        val userId = (payload["userId"] as? Number)?.toLong()
            ?: throw ExpectedException("state의 userId 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)

        // 복귀 Origin이 없는 기존 state는 허용 목록의 첫 번째 주소를 사용한다.
        val returnOrigin = if (payload.containsKey("returnOrigin")) {
            payload["returnOrigin"] as? String
                ?: throw ExpectedException("복귀 Origin이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        } else {
            oAuthProperties.allowedReturnOrigins.first()
        }
        return OAuthState(channelId, userId, validateReturnOrigin(returnOrigin))
    }

    private fun validateReturnOrigin(origin: String): String {
        val uri = runCatching { URI(origin) }.getOrNull()
        if (uri == null ||
            uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() ||
            uri.rawQuery != null || uri.rawFragment != null ||
            origin !in oAuthProperties.allowedReturnOrigins
        ) {
            throw ExpectedException("허용되지 않은 복귀 Origin입니다.", HttpStatus.BAD_REQUEST)
        }
        return origin
    }

    private fun hmacSign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(oAuthProperties.stateSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }
}
