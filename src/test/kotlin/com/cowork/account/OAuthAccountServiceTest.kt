package com.cowork.account

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.oauth.AccountOAuthProperties
import com.cowork.channel.account.oauth.OAuthProviderClient
import com.cowork.channel.account.oauth.OAuthStateCodec
import com.cowork.channel.account.service.OAuthAccountService
import com.cowork.channel.account.service.SharedAccountService
import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.global.error.BusinessException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class OAuthAccountServiceTest {

    private val github = AccountOAuthProperties.Provider(
        clientId = "client",
        clientSecret = "secret",
        authorizeUrl = "https://github.com/login/oauth/authorize",
        tokenUrl = "https://github.com/login/oauth/access_token",
        userInfoUrl = "https://api.github.com/user",
        scope = "read:user",
        identifierField = "login",
    )
    private val properties = AccountOAuthProperties(
        clientRedirectUrl = "https://cowork.team/",
        callbackBaseUrl = "https://api.cowork.team/",
        providers = mapOf("github" to github),
    )
    private val stateCodec = mockk<OAuthStateCodec>()
    private val providerClient = mockk<OAuthProviderClient>()
    private val sharedAccountService = mockk<SharedAccountService>()
    private val service = OAuthAccountService(properties, stateCodec, providerClient, sharedAccountService)

    @BeforeEach
    fun allowChannel() {
        every { sharedAccountService.requireAccountChannel(20, 1) } returns Channel(
            teamId = 1, projectId = null, name = "accounts", viewType = ChannelViewType.ACCOUNT_SHARE, description = null,
            isPrivate = false, position = 0, createdBy = 1, id = 20,
        )
    }

    @Test
    fun `인증 URL에 클라이언트 ID, 콜백 주소, state를 담는다`() {
        every { stateCodec.encode(OAuthStateCodec.OAuthState(20, 1, AccountProvider.GITHUB)) } returns "signed-state"

        val url = service.buildAuthorizeUrl(20, 1, AccountProvider.GITHUB)

        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?client_id=client"))
        assertTrue(url.contains("redirect_uri=https://api.cowork.team/channels/oauth/callback/github"))
        assertTrue(url.contains("state=signed-state"))
        assertTrue(url.contains("scope=read:user"))
    }

    @Test
    fun `OAuth를 지원하지 않는 서비스는 400을 반환한다`() {
        val exception = assertThrows<BusinessException> { service.buildAuthorizeUrl(20, 1, AccountProvider.NPM) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `설정하지 않은 OAuth 서비스는 400을 반환한다`() {
        assertThrows<BusinessException> { service.buildAuthorizeUrl(20, 1, AccountProvider.NOTION) }
    }
}
