package com.cowork.channel.service

import com.cowork.channel.config.OAuthProperties
import com.cowork.channel.config.OAuthProviderConfig
import com.cowork.channel.domain.AccountProvider
import com.cowork.channel.domain.SharedAccount
import com.cowork.channel.repository.SharedAccountRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class OAuthAccountService(
    private val oAuthProperties: OAuthProperties,
    private val sharedAccountRepository: SharedAccountRepository,
    private val credentialEncryptionService: CredentialEncryptionService,
) {
    private val restClient = RestClient.create()
    private val objectMapper = ObjectMapper()

    fun buildAuthorizeUrl(channelId: Long, userId: Long, provider: AccountProvider): String {
        val config = providerConfigOf(provider)
        val state = buildState(channelId, userId, provider)
        val callbackUrl = "${oAuthProperties.callbackBaseUrl}/channels/oauth/callback/${provider.name.lowercase()}"

        return when (provider) {
            AccountProvider.GITHUB -> UriComponentsBuilder
                .fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", config.clientId)
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("scope", config.scope)
                .queryParam("state", state)
                .build().toUriString()

            AccountProvider.NOTION -> UriComponentsBuilder
                .fromUriString("https://api.notion.com/v1/oauth/authorize")
                .queryParam("client_id", config.clientId)
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("response_type", "code")
                .queryParam("owner", "user")
                .queryParam("state", state)
                .build().toUriString()

            AccountProvider.JIRA -> UriComponentsBuilder
                .fromUriString("https://auth.atlassian.com/authorize")
                .queryParam("client_id", config.clientId)
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("response_type", "code")
                .queryParam("scope", config.scope)
                .queryParam("state", state)
                .queryParam("prompt", "consent")
                .build().toUriString()

            AccountProvider.GOOGLE -> UriComponentsBuilder
                .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", config.clientId)
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("response_type", "code")
                .queryParam("scope", config.scope)
                .queryParam("state", state)
                .queryParam("access_type", "online")
                .build().toUriString()

            AccountProvider.FACEBOOK -> UriComponentsBuilder
                .fromUriString("https://www.facebook.com/v18.0/dialog/oauth")
                .queryParam("client_id", config.clientId)
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("scope", config.scope)
                .queryParam("state", state)
                .build().toUriString()

            else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다. provider=${provider.name}", HttpStatus.BAD_REQUEST)
        }
    }

    @Transactional
    fun handleCallback(providerName: String, code: String, state: String): SharedAccount {
        val provider = runCatching { AccountProvider.valueOf(providerName.uppercase()) }.getOrElse {
            throw ExpectedException("지원하지 않는 OAuth provider입니다. provider=$providerName", HttpStatus.BAD_REQUEST)
        }

        val (channelId, userId) = verifyState(state, provider)
        val config = providerConfigOf(provider)
        val callbackUrl = "${oAuthProperties.callbackBaseUrl}/channels/oauth/callback/${provider.name.lowercase()}"

        val accessToken = exchangeCode(provider, config, code, callbackUrl)
        val identifier = fetchIdentifier(provider, config, accessToken)

        return sharedAccountRepository.save(
            SharedAccount(
                channelId = channelId,
                provider = provider,
                providerLabel = null,
                accountIdentifier = identifier,
                credential = null,
                connectedViaOAuth = true,
                createdBy = userId,
            )
        )
    }

    private fun exchangeCode(
        provider: AccountProvider,
        config: OAuthProviderConfig,
        code: String,
        callbackUrl: String,
    ): String {
        return when (provider) {
            AccountProvider.GITHUB -> {
                val body = LinkedMultiValueMap<String, String>().apply {
                    add("client_id", config.clientId)
                    add("client_secret", config.clientSecret)
                    add("code", code)
                    add("redirect_uri", callbackUrl)
                }
                val response = restClient.post()
                    .uri(config.tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map::class.java) ?: throw ExpectedException("GitHub 토큰 교환 실패", HttpStatus.BAD_GATEWAY)
                response["access_token"] as? String
                    ?: throw ExpectedException("GitHub access_token 없음", HttpStatus.BAD_GATEWAY)
            }

            AccountProvider.NOTION -> {
                val credentials = Base64.getEncoder()
                    .encodeToString("${config.clientId}:${config.clientSecret}".toByteArray())
                val requestBody = mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to callbackUrl)
                val response = restClient.post()
                    .uri(config.tokenUrl)
                    .header("Authorization", "Basic $credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map::class.java) ?: throw ExpectedException("Notion 토큰 교환 실패", HttpStatus.BAD_GATEWAY)
                response["access_token"] as? String
                    ?: throw ExpectedException("Notion access_token 없음", HttpStatus.BAD_GATEWAY)
            }

            AccountProvider.JIRA, AccountProvider.GOOGLE, AccountProvider.FACEBOOK -> {
                val requestBody = mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to config.clientId,
                    "client_secret" to config.clientSecret,
                    "code" to code,
                    "redirect_uri" to callbackUrl,
                )
                val response = restClient.post()
                    .uri(config.tokenUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map::class.java) ?: throw ExpectedException("${provider.displayName} 토큰 교환 실패", HttpStatus.BAD_GATEWAY)
                response["access_token"] as? String
                    ?: throw ExpectedException("${provider.displayName} access_token 없음", HttpStatus.BAD_GATEWAY)
            }

            else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다.", HttpStatus.BAD_REQUEST)
        }
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
                ?: (response["email"] as? String)
            AccountProvider.NOTION -> {
                @Suppress("UNCHECKED_CAST")
                val person = (response["person"] as? Map<String, Any>)
                person?.get("email") as? String
            }
            AccountProvider.JIRA -> response["email"] as? String
            AccountProvider.GOOGLE -> response["email"] as? String
            AccountProvider.FACEBOOK -> response["email"] as? String
            else -> null
        }
    }

    // state = base64url(json_payload).base64url(hmac-sha256)
    // payload: { channelId, userId, provider, nonce, exp }
    private fun buildState(channelId: Long, userId: Long, provider: AccountProvider): String {
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

    private fun verifyState(state: String, provider: AccountProvider): Pair<Long, Long> {
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

    private fun providerConfigOf(provider: AccountProvider) = when (provider) {
        AccountProvider.GITHUB -> oAuthProperties.github
        AccountProvider.NOTION -> oAuthProperties.notion
        AccountProvider.JIRA -> oAuthProperties.jira
        AccountProvider.GOOGLE -> oAuthProperties.google
        AccountProvider.FACEBOOK -> oAuthProperties.facebook
        else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다. provider=${provider.name}", HttpStatus.BAD_REQUEST)
    }
}
