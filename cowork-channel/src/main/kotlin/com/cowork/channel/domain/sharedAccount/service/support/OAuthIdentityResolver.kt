package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import team.themoment.sdk.exception.ExpectedException
import java.time.Duration
import java.util.Base64

/**
 * OAuth 제공자와의 외부 HTTP 통신만 담당한다.
 *
 * 이 컴포넌트는 database transaction 밖에서 호출되어야 한다. 제공자 응답이 느릴 때
 * connection pool을 점유하지 않도록 호출자가 transaction 경계를 분리한다.
 */
@Component
class OAuthIdentityResolver(
    restClientBuilder: RestClient.Builder,
    oAuthProperties: OAuthProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val restClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(oAuthProperties.connectTimeoutMs))
                setReadTimeout(Duration.ofMillis(oAuthProperties.readTimeoutMs))
            },
        )
        .build()

    /** 인가 코드를 access token으로 교환한 뒤 제공자 측 사용자 식별자를 조회한다. */
    fun resolveIdentifier(
        provider: AccountProvider,
        config: OAuthProviderConfig,
        code: String,
        callbackUrl: String,
    ): String {
        val accessToken = timed(provider, "token_exchange") { exchangeCode(provider, config, code, callbackUrl) }
        return timed(provider, "userinfo") { fetchIdentifier(provider, config, accessToken) }
            ?: throw ExpectedException("사용자 식별자를 가져오지 못했습니다.", HttpStatus.BAD_GATEWAY)
    }

    private fun <T> timed(provider: AccountProvider, stage: String, block: () -> T): T {
        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        return try {
            block()
        } catch (exception: Throwable) {
            outcome = "failure"
            throw exception
        } finally {
            sample.stop(
                Timer.builder(EXTERNAL_CALL_METRIC)
                    .tag("provider", provider.name)
                    .tag("stage", stage)
                    .tag("outcome", outcome)
                    .register(meterRegistry),
            )
        }
    }

    private fun exchangeCode(
        provider: AccountProvider,
        config: OAuthProviderConfig,
        code: String,
        callbackUrl: String,
    ): String = when (provider) {
        AccountProvider.GITHUB -> {
            val body = LinkedMultiValueMap<String, String>().apply {
                add("client_id", config.clientId)
                add("client_secret", config.clientSecret)
                add("code", code)
                add("redirect_uri", callbackUrl)
            }
            // GitHub은 Accept를 지정하지 않으면 form-encoded로 응답한다.
            postForAccessToken(config.tokenUrl, MediaType.APPLICATION_FORM_URLENCODED, body) {
                accept(MediaType.APPLICATION_JSON)
            }
        }

        AccountProvider.NOTION -> {
            val credentials = Base64.getEncoder()
                .encodeToString("${config.clientId}:${config.clientSecret}".toByteArray())
            val requestBody = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to callbackUrl,
            )
            postForAccessToken(config.tokenUrl, MediaType.APPLICATION_JSON, requestBody) {
                header("Authorization", "Basic $credentials")
            }
        }

        AccountProvider.JIRA, AccountProvider.GOOGLE, AccountProvider.FACEBOOK -> {
            val body = LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", config.clientId)
                add("client_secret", config.clientSecret)
                add("code", code)
                add("redirect_uri", callbackUrl)
            }
            postForAccessToken(config.tokenUrl, MediaType.APPLICATION_FORM_URLENCODED, body)
        }

        else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다.", HttpStatus.BAD_REQUEST)
    }

    private fun postForAccessToken(
        tokenUrl: String,
        contentType: MediaType,
        body: Any,
        customize: RestClient.RequestBodySpec.() -> Unit = {},
    ): String {
        val response = restClient.post()
            .uri(tokenUrl)
            .contentType(contentType)
            .apply(customize)
            .body(body)
            .retrieve()
            .body(Map::class.java)
            ?: throw ExpectedException("OAuth 토큰 교환에 실패했습니다.", HttpStatus.BAD_GATEWAY)

        return response["access_token"] as? String
            ?: throw ExpectedException("OAuth 토큰 교환에 실패했습니다.", HttpStatus.BAD_GATEWAY)
    }

    private fun fetchIdentifier(provider: AccountProvider, config: OAuthProviderConfig, accessToken: String): String? {
        val response = restClient.get()
            .uri(config.userinfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .also {
                if (provider == AccountProvider.NOTION) it.header("Notion-Version", "2022-06-28")
            }
            .retrieve()
            .body(Map::class.java) ?: return null

        return when (provider) {
            AccountProvider.GITHUB -> response["login"] as? String
            AccountProvider.NOTION -> response["id"] as? String
            AccountProvider.JIRA -> response["account_id"] as? String
            AccountProvider.GOOGLE -> response["sub"] as? String
            AccountProvider.FACEBOOK -> response["id"] as? String
            else -> null
        }
    }

    private companion object {
        const val EXTERNAL_CALL_METRIC = "channel.oauth.external.call"
    }
}
