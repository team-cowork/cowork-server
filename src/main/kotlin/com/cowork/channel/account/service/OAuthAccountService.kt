package com.cowork.channel.account.service

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.domain.SharedAccount
import com.cowork.channel.account.oauth.AccountOAuthProperties
import com.cowork.channel.account.oauth.OAuthProviderClient
import com.cowork.channel.account.oauth.OAuthStateCodec
import com.cowork.global.error.BusinessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

@Service
class OAuthAccountService(
    private val properties: AccountOAuthProperties,
    private val stateCodec: OAuthStateCodec,
    private val providerClient: OAuthProviderClient,
    private val sharedAccountService: SharedAccountService,
) {

    fun buildAuthorizeUrl(channelId: Long, userId: Long, provider: AccountProvider): String {
        sharedAccountService.requireAccountChannel(channelId, userId)
        val config = requireProvider(provider)
        val state = stateCodec.encode(OAuthStateCodec.OAuthState(channelId, userId, provider))
        return UriComponentsBuilder.fromUriString(config.authorizeUrl)
            .queryParam("client_id", config.clientId)
            .queryParam("redirect_uri", callbackUrl(provider))
            .queryParam("response_type", "code")
            .queryParam("state", state)
            .apply { if (config.scope.isNotBlank()) queryParam("scope", config.scope) }
            .encode()
            .build()
            .toUriString()
    }

    fun handleCallback(providerName: String, code: String, stateToken: String): SharedAccount {
        val provider = AccountProvider.entries.firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: throw BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 서비스입니다.")
        val state = stateCodec.decode(stateToken)
        if (state == null || state.provider != provider) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "유효하지 않은 인증 요청입니다.")
        }
        sharedAccountService.requireAccountChannel(state.channelId, state.userId)

        val config = requireProvider(provider)
        val accessToken = providerClient.exchangeCode(config, code, callbackUrl(provider))
        val identifier = providerClient.fetchIdentifier(config, accessToken)
        return sharedAccountService.saveOAuthAccount(state.channelId, state.userId, provider, identifier, accessToken)
    }

    fun clientRedirectUrl(): String = properties.clientRedirectUrl.trimEnd('/')

    private fun requireProvider(provider: AccountProvider): AccountOAuthProperties.Provider {
        if (!provider.oauthSupported) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "OAuth 연결을 지원하지 않는 서비스입니다.")
        }
        return properties.provider(provider.name)
            ?: throw BusinessException(HttpStatus.BAD_REQUEST, "OAuth 연결이 설정되지 않은 서비스입니다.")
    }

    private fun callbackUrl(provider: AccountProvider): String =
        "${properties.callbackBaseUrl.trimEnd('/')}/channels/oauth/callback/${provider.name.lowercase()}"
}
