package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
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

    fun providerConfigOf(provider: AccountProvider): OAuthProviderConfig = when (provider) {
        AccountProvider.GITHUB -> oAuthProperties.github
        AccountProvider.NOTION -> oAuthProperties.notion
        AccountProvider.JIRA -> oAuthProperties.jira
        AccountProvider.GOOGLE -> oAuthProperties.google
        AccountProvider.FACEBOOK -> oAuthProperties.facebook
        else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다.", HttpStatus.BAD_REQUEST)
    }

    // state = base64url(json_payload).base64url(hmac-sha256)
    // payload: { channelId, userId, provider, returnOrigin, nonce, exp }
    fun buildState(
        channelId: Long,
        userId: Long,
        provider: AccountProvider,
        returnOrigin: String? = null,
        nonce: String = UUID.randomUUID().toString(),
    ): String {
        val payload = mapOf(
            "channelId" to channelId,
            "userId" to userId,
            "provider" to provider.name,
            "returnOrigin" to validateReturnOrigin(returnOrigin ?: oAuthProperties.allowedReturnOrigins.first()),
            "nonce" to nonce,
            "exp" to (Instant.now().epochSecond + STATE_TTL.seconds),
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
        return OAuthState(channelId, userId, validateReturnOrigin(returnOrigin), payload["nonce"] as? String)
    }

    /** 콜백 요청 쿠키의 nonce가 state의 nonce와 같아야 흐름을 시작한 브라우저로 인정한다. */
    fun requireBrowserNonce(state: OAuthState, browserNonce: String?) {
        val expected = state.nonce
        if (expected.isNullOrBlank() || browserNonce.isNullOrBlank() ||
            !MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), browserNonce.toByteArray(Charsets.UTF_8))
        ) {
            throw ExpectedException("OAuth 연결을 시작한 브라우저의 요청이 아닙니다.", HttpStatus.BAD_REQUEST)
        }
    }

    /** 인증 시작 응답에 내리는 nonce 쿠키. 해당 provider 콜백 경로에만 전송된다. */
    fun nonceCookie(provider: AccountProvider, nonce: String): ResponseCookie =
        nonceCookieBuilder(provider, nonce).maxAge(STATE_TTL).build()

    /** 콜백 응답에서 nonce 쿠키를 지워 같은 브라우저에서도 다시 쓰지 못하게 한다. */
    fun expiredNonceCookie(provider: AccountProvider): ResponseCookie =
        nonceCookieBuilder(provider, "").maxAge(Duration.ZERO).build()

    // 웹 Origin과 API 주소가 달라 교차 출처 요청으로 쿠키를 받으므로 SameSite=None, Secure를 쓴다.
    private fun nonceCookieBuilder(provider: AccountProvider, value: String) =
        ResponseCookie.from(NONCE_COOKIE_NAME, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("None")
            .path(URI(oAuthProperties.callbackUrl(provider.name)).rawPath)

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

    companion object {
        const val NONCE_COOKIE_NAME = "cowork_oauth_nonce"
        private val STATE_TTL: Duration = Duration.ofSeconds(300)
    }
}
