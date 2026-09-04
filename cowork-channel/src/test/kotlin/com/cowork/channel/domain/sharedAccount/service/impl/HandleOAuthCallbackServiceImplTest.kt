package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.support.OAuthStateSupport
import com.cowork.channel.global.config.OAuthProperties
import com.cowork.channel.global.config.OAuthProviderConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HandleOAuthCallbackServiceImplTest {

    private val objectMapper = jacksonObjectMapper()
    private val STATE_SECRET = "test-state-secret-key"

    private val oAuthProperties = OAuthProperties(
        callbackBaseUrl = "https://example.com",
        clientRedirectUrl = "https://client.example.com",
        stateSecret = STATE_SECRET,
        github = OAuthProviderConfig(
            "gh-id",
            "gh-secret",
            "https://github.com/login/oauth/access_token",
            "https://api.github.com/user",
            "read:user",
        ),
        notion = OAuthProviderConfig(
            "no-id",
            "no-secret",
            "https://api.notion.com/v1/oauth/token",
            "https://api.notion.com/v1/users/me",
            "",
        ),
        jira = OAuthProviderConfig(
            "jira-id",
            "jira-secret",
            "https://auth.atlassian.com/oauth/token",
            "https://api.atlassian.com/me",
            "read:me",
        ),
        google = OAuthProviderConfig(
            "go-id",
            "go-secret",
            "https://oauth2.googleapis.com/token",
            "https://openidconnect.googleapis.com/v1/userinfo",
            "openid email",
        ),
        facebook = OAuthProviderConfig(
            "fb-id",
            "fb-secret",
            "https://graph.facebook.com/v18.0/oauth/access_token",
            "https://graph.facebook.com/me",
            "public_profile",
        ),
    )

    private val sharedAccountRepository = mockk<SharedAccountRepository>(relaxed = true)
    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val oAuthStateSupport = OAuthStateSupport(oAuthProperties, objectMapper)

    private val mockRestClient = mockk<RestClient>(relaxed = true)
    private val requestBodySlot = slot<Any>()
    private val restClientBuilder = mockk<RestClient.Builder>().also {
        every { it.build() } returns mockRestClient
    }

    private val service = HandleOAuthCallbackServiceImpl(
        oAuthProperties,
        sharedAccountRepository,
        channelAccessGuard,
        teamPermissionService,
        oAuthStateSupport,
        restClientBuilder,
    )

    private fun accountShareChannel(teamId: Long = 100L) = Channel(
        id = 1L, teamId = teamId, name = "ch", type = ChannelType.TEXT,
        viewType = ChannelViewType.ACCOUNT_SHARE, description = null,
        isPrivate = false, position = 0, createdBy = 1L, projectId = null,
    )

    private fun buildValidState(
        channelId: Long = 1L,
        userId: Long = 1L,
        provider: AccountProvider = AccountProvider.GITHUB,
        expOffset: Long = 300L,
    ): String {
        val payload = mapOf(
            "channelId" to channelId,
            "userId" to userId,
            "provider" to provider.name,
            "nonce" to UUID.randomUUID().toString(),
            "exp" to (Instant.now().epochSecond + expOffset),
        )
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payloadB64 = encoder.encodeToString(
            objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8),
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(STATE_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = encoder.encodeToString(mac.doFinal(payloadB64.toByteArray(Charsets.UTF_8)))
        return "$payloadB64.$signature"
    }

    private fun stubPostReturns(responseBody: Map<*, *>) {
        val postBodySpec = mockk<RestClient.RequestBodySpec>()
        every { postBodySpec.header(any(), any()) } returns postBodySpec
        every { postBodySpec.contentType(any()) } returns postBodySpec
        every { postBodySpec.accept(any()) } returns postBodySpec
        every { postBodySpec.body(capture(requestBodySlot)) } returns postBodySpec
        val postResponseSpec = mockk<RestClient.ResponseSpec>()
        every { postBodySpec.retrieve() } returns postResponseSpec
        every { postResponseSpec.body(Map::class.java) } returns responseBody

        val postUriSpec = mockk<RestClient.RequestBodyUriSpec>()
        every { postUriSpec.uri(any<String>()) } returns postBodySpec
        every { mockRestClient.post() } returns postUriSpec
    }

    private fun stubGetReturns(responseBody: Map<*, *>) {
        val getHeadersSpec = mockk<RestClient.RequestHeadersSpec<*>>()
        every { getHeadersSpec.header(any(), any()) } returns getHeadersSpec
        val getResponseSpec = mockk<RestClient.ResponseSpec>()
        every { getHeadersSpec.retrieve() } returns getResponseSpec
        every { getResponseSpec.body(Map::class.java) } returns responseBody

        val getUriSpec = mockk<RestClient.RequestHeadersUriSpec<*>>()
        every { getUriSpec.uri(any<String>()) } returns getHeadersSpec
        every { mockRestClient.get() } returns getUriSpec
    }

    @Test
    fun `handleCallback는 지원하지 않는 provider 이름이면 BAD_REQUEST`() {
        val ex = assertThrows<ExpectedException> {
            service.handleCallback("UNKNOWN_PROVIDER", "code", "state")
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `handleCallback는 서명이 변조된 state이면 BAD_REQUEST`() {
        val payloadB64 = buildValidState().split(".")[0]

        val ex = assertThrows<ExpectedException> {
            service.handleCallback("github", "code", "$payloadB64.invalidsignature")
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `handleCallback는 만료된 state이면 BAD_REQUEST`() {
        val expiredState = buildValidState(expOffset = -1L)

        val ex = assertThrows<ExpectedException> {
            service.handleCallback("github", "code", expiredState)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `handleCallback는 provider가 state와 불일치하면 BAD_REQUEST`() {
        val githubState = buildValidState(provider = AccountProvider.GITHUB)

        val ex = assertThrows<ExpectedException> {
            service.handleCallback("notion", "code", githubState)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `handleCallback는 이미 등록된 계정이면 기존 계정을 반환하고 save를 호출하지 않음`() {
        val existingAccount = SharedAccount(
            id = 10L,
            channelId = 1L,
            provider = AccountProvider.GITHUB,
            providerLabel = null,
            accountIdentifier = "ghuser",
            credential = null,
            connectedViaOAuth = true,
            createdBy = 1L,
        )
        val validState = buildValidState(channelId = 1L, userId = 1L, provider = AccountProvider.GITHUB)

        stubPostReturns(mapOf("access_token" to "gh_token"))
        stubGetReturns(mapOf("login" to "ghuser"))

        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every {
            sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(
                1L,
                AccountProvider.GITHUB,
                "ghuser",
            )
        } returns existingAccount

        val result = service.handleCallback("github", "auth-code", validState)

        assertEquals(existingAccount.id, result.id)
        verify(exactly = 0) { sharedAccountRepository.save(any()) }
    }
}
