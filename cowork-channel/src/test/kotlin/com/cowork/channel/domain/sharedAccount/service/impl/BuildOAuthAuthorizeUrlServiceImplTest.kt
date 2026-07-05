package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.OAuthStateSupport
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Optional

class BuildOAuthAuthorizeUrlServiceImplTest {

    private val objectMapper = jacksonObjectMapper()

    private val oAuthProperties = OAuthProperties(
        callbackBaseUrl = "https://example.com",
        clientRedirectUrl = "https://client.example.com",
        stateSecret = "test-state-secret-key",
        github = OAuthProviderConfig(
            "gh-id",
            "gh-secret",
            "https://github.com/login/oauth/access_token",
            "https://api.github.com/user",
            "read:user",
        ),
        notion = OAuthProviderConfig("no-id", "no-secret", "https://api.notion.com/v1/oauth/token", "https://api.notion.com/v1/users/me", ""),
        jira = OAuthProviderConfig("jira-id", "jira-secret", "https://auth.atlassian.com/oauth/token", "https://api.atlassian.com/me", "read:me"),
        google = OAuthProviderConfig("go-id", "go-secret", "https://oauth2.googleapis.com/token", "https://openidconnect.googleapis.com/v1/userinfo", "openid email"),
        facebook = OAuthProviderConfig("fb-id", "fb-secret", "https://graph.facebook.com/v18.0/oauth/access_token", "https://graph.facebook.com/me", "public_profile"),
    )

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val sharedAccountAccessGuard = SharedAccountAccessGuard()
    private val oAuthStateSupport = OAuthStateSupport(oAuthProperties, objectMapper)

    private val service = BuildOAuthAuthorizeUrlServiceImpl(
        oAuthProperties,
        channelAccessGuard,
        teamPermissionService,
        sharedAccountAccessGuard,
        oAuthStateSupport,
    )

    private fun accountShareChannel(teamId: Long = 100L) = Channel(
        id = 1L, teamId = teamId, name = "ch", type = ChannelType.TEXT,
        viewType = ChannelViewType.ACCOUNT_SHARE, description = null,
        isPrivate = false, position = 0, createdBy = 1L, projectId = null,
    )

    private fun textChannel() = Channel(
        id = 1L, teamId = 100L, name = "ch", type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT, description = null,
        isPrivate = false, position = 0, createdBy = 1L, projectId = null,
    )

    @Test
    fun `buildAuthorizeUrl는 ACCOUNT_SHARE 채널이 아니면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(textChannel())

        val ex = assertThrows<ExpectedException> {
            service.buildAuthorizeUrl(1L, 1L, AccountProvider.GITHUB)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `buildAuthorizeUrl는 팀 비멤버이면 FORBIDDEN`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows<ExpectedException> {
            service.buildAuthorizeUrl(1L, 7L, AccountProvider.GITHUB)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `buildAuthorizeUrl는 OAuth 미지원 provider이면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit

        val ex = assertThrows<ExpectedException> {
            service.buildAuthorizeUrl(1L, 1L, AccountProvider.NPM)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `buildAuthorizeUrl는 GITHUB provider이면 github 인증 URL을 반환함`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit

        val url = service.buildAuthorizeUrl(1L, 1L, AccountProvider.GITHUB)

        assertTrue(url.startsWith("https://github.com/login/oauth/authorize"))
        assertTrue(url.contains("client_id=gh-id"))
        assertTrue(url.contains("state="))
    }
}
