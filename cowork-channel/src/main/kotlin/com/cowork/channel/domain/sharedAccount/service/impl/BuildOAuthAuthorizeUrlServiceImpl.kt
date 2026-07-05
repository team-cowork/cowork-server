package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.service.BuildOAuthAuthorizeUrlService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.OAuthStateSupport
import com.cowork.channel.global.config.OAuthProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import team.themoment.sdk.exception.ExpectedException

@Service
class BuildOAuthAuthorizeUrlServiceImpl(
    private val oAuthProperties: OAuthProperties,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val sharedAccountAccessGuard: SharedAccountAccessGuard,
    private val oAuthStateSupport: OAuthStateSupport,
) : BuildOAuthAuthorizeUrlService {

    override fun buildAuthorizeUrl(channelId: Long, userId: Long, provider: AccountProvider): String {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        sharedAccountAccessGuard.requireAccountShareChannel(channel)
        teamPermissionService.requireTeamMember(channelAccessGuard.requireTeamChannel(channel), userId)
        val config = oAuthStateSupport.providerConfigOf(provider)
        val state = oAuthStateSupport.buildState(channelId, userId, provider)
        val callbackUrl = "${oAuthProperties.callbackBaseUrl}/channels/oauth/callback/${provider.name.lowercase()}"

        return when (provider) {
            AccountProvider.GITHUB ->
                UriComponentsBuilder
                    .fromUriString("https://github.com/login/oauth/authorize")
                    .queryParam("client_id", config.clientId)
                    .queryParam("redirect_uri", callbackUrl)
                    .queryParam("scope", config.scope)
                    .queryParam("state", state)
                    .build().toUriString()

            AccountProvider.NOTION ->
                UriComponentsBuilder
                    .fromUriString("https://api.notion.com/v1/oauth/authorize")
                    .queryParam("client_id", config.clientId)
                    .queryParam("redirect_uri", callbackUrl)
                    .queryParam("response_type", "code")
                    .queryParam("owner", "user")
                    .queryParam("state", state)
                    .build().toUriString()

            AccountProvider.JIRA ->
                UriComponentsBuilder
                    .fromUriString("https://auth.atlassian.com/authorize")
                    .queryParam("client_id", config.clientId)
                    .queryParam("redirect_uri", callbackUrl)
                    .queryParam("response_type", "code")
                    .queryParam("scope", config.scope)
                    .queryParam("state", state)
                    .queryParam("prompt", "consent")
                    .build().toUriString()

            AccountProvider.GOOGLE ->
                UriComponentsBuilder
                    .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                    .queryParam("client_id", config.clientId)
                    .queryParam("redirect_uri", callbackUrl)
                    .queryParam("response_type", "code")
                    .queryParam("scope", config.scope)
                    .queryParam("state", state)
                    .queryParam("access_type", "online")
                    .build().toUriString()

            AccountProvider.FACEBOOK ->
                UriComponentsBuilder
                    .fromUriString("https://www.facebook.com/v18.0/dialog/oauth")
                    .queryParam("client_id", config.clientId)
                    .queryParam("redirect_uri", callbackUrl)
                    .queryParam("scope", config.scope)
                    .queryParam("state", state)
                    .build().toUriString()

            else -> throw ExpectedException("OAuth를 지원하지 않는 서비스입니다.", HttpStatus.BAD_REQUEST)
        }
    }
}
