package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.HandleOAuthCallbackService
import com.cowork.channel.domain.sharedAccount.service.support.OAuthStateSupport
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import team.themoment.sdk.exception.ExpectedException
import java.util.Base64

@Service
@Transactional
class HandleOAuthCallbackServiceImpl(
    private val oAuthProperties: OAuthProperties,
    private val sharedAccountRepository: SharedAccountRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val oAuthStateSupport: OAuthStateSupport,
    restClientBuilder: RestClient.Builder,
) : HandleOAuthCallbackService {
    private val restClient = restClientBuilder.build()

    override fun handleCallback(providerName: String, code: String, state: String): SharedAccount {
        val provider = runCatching { AccountProvider.valueOf(providerName.uppercase()) }.getOrElse {
            throw ExpectedException("지원하지 않는 OAuth provider입니다.", HttpStatus.BAD_REQUEST)
        }

        val (channelId, userId) = oAuthStateSupport.verifyState(state, provider)
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channelAccessGuard.requireTeamChannel(channel), userId)
        val config = oAuthStateSupport.providerConfigOf(provider)
        val callbackUrl = "${oAuthProperties.callbackBaseUrl}/channels/oauth/callback/${provider.name.lowercase()}"

        val accessToken = exchangeCode(provider, config, code, callbackUrl)
        val identifier = fetchIdentifier(provider, config, accessToken)
            ?: throw ExpectedException("사용자 식별자를 가져오지 못했습니다.", HttpStatus.BAD_GATEWAY)

        sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(channelId, provider, identifier)
            ?.let { return it }

        return sharedAccountRepository.save(
            SharedAccount(
                channelId = channelId,
                provider = provider,
                providerLabel = null,
                accountIdentifier = identifier,
                credential = null,
                connectedViaOAuth = true,
                createdBy = userId,
            ),
        )
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
            val requestBody = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to callbackUrl,
            )
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

        AccountProvider.JIRA -> {
            val body = LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", config.clientId)
                add("client_secret", config.clientSecret)
                add("code", code)
                add("redirect_uri", callbackUrl)
            }
            val response = restClient.post()
                .uri(config.tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map::class.java) ?: throw ExpectedException("Jira 토큰 교환 실패", HttpStatus.BAD_GATEWAY)
            response["access_token"] as? String
                ?: throw ExpectedException("Jira access_token 없음", HttpStatus.BAD_GATEWAY)
        }

        AccountProvider.GOOGLE, AccountProvider.FACEBOOK -> {
            val body = LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", config.clientId)
                add("client_secret", config.clientSecret)
                add("code", code)
                add("redirect_uri", callbackUrl)
            }
            val response = restClient.post()
                .uri(config.tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map::class.java)
                ?: throw ExpectedException("OAuth 토큰 교환에 실패했습니다.", HttpStatus.BAD_GATEWAY)
            response["access_token"] as? String
                ?: throw ExpectedException("OAuth 토큰 교환에 실패했습니다.", HttpStatus.BAD_GATEWAY)
        }

        else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다.", HttpStatus.BAD_REQUEST)
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
}
