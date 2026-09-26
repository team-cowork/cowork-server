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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Optional

class BuildOAuthAuthorizeUrlServiceImplTest :
    DescribeSpec({
        val defaultOrigin = "https://client.example.com"
        val otherOrigin = "https://admin.example.com"
        val properties = OAuthProperties(
            callbackBaseUrl = "https://example.com",
            allowedReturnOrigins = listOf(defaultOrigin, otherOrigin),
            stateSecret = "test-state-secret-key",
            github = OAuthProviderConfig(
                "gh-id",
                "gh-secret",
                "https://github.com/login/oauth/access_token",
                "https://api.github.com/user",
                "read:user",
            ),
        )
        val stateSupport = OAuthStateSupport(properties, jacksonObjectMapper())
        val channel = Channel(
            id = 1L, teamId = 100L, name = "ch", type = ChannelType.TEXT,
            viewType = ChannelViewType.ACCOUNT_SHARE, description = null,
            isPrivate = false, position = 0, createdBy = 1L, projectId = null,
        )
        lateinit var channelRepository: ChannelRepository
        lateinit var teamPermissionService: TeamPermissionService
        lateinit var service: BuildOAuthAuthorizeUrlServiceImpl

        beforeEach {
            channelRepository = mockk()
            teamPermissionService = mockk()
            every { channelRepository.findById(1L) } returns Optional.of(channel)
            every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
            service = BuildOAuthAuthorizeUrlServiceImpl(
                properties,
                ChannelAccessGuard(channelRepository),
                teamPermissionService,
                SharedAccountAccessGuard(),
                stateSupport,
            )
        }

        describe("buildAuthorizeUrl 메서드는") {
            it("ACCOUNT_SHARE 채널이 아니면 BAD_REQUEST를 반환한다") {
                val textChannel = Channel(
                    id = 1L, teamId = 100L, name = "ch", type = ChannelType.TEXT,
                    viewType = ChannelViewType.TEXT, description = null,
                    isPrivate = false, position = 0, createdBy = 1L, projectId = null,
                )
                every { channelRepository.findById(1L) } returns Optional.of(textChannel)

                shouldThrow<ExpectedException> {
                    service.buildAuthorizeUrl(1L, 1L, AccountProvider.GITHUB)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("팀 비멤버이면 FORBIDDEN을 반환한다") {
                every { teamPermissionService.requireTeamMember(100L, 7L) } throws
                    ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

                shouldThrow<ExpectedException> {
                    service.buildAuthorizeUrl(1L, 7L, AccountProvider.GITHUB, otherOrigin)
                }.statusCode shouldBe HttpStatus.FORBIDDEN
            }

            it("OAuth 미지원 provider이면 BAD_REQUEST를 반환한다") {
                shouldThrow<ExpectedException> {
                    service.buildAuthorizeUrl(1L, 1L, AccountProvider.NPM)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("복귀 Origin을 생략하면 기본 주소를 담은 GitHub 인증 URL을 반환한다") {
                val url = service.buildAuthorizeUrl(1L, 1L, AccountProvider.GITHUB)
                val params = UriComponentsBuilder.fromUriString(url).build().queryParams

                url shouldStartWith "https://github.com/login/oauth/authorize"
                params.getFirst("client_id") shouldBe "gh-id"
                params.getFirst("redirect_uri") shouldBe
                    "https://example.com/api/channel/channels/oauth/callback/github"
                stateSupport.verifyState(
                    requireNotNull(params.getFirst("state")),
                    AccountProvider.GITHUB,
                ).returnOrigin shouldBe
                    defaultOrigin
            }

            it("선택한 복귀 Origin을 state에 저장하고 provider 콜백은 서버 주소를 유지한다") {
                val url = service.buildAuthorizeUrl(1L, 1L, AccountProvider.GITHUB, otherOrigin)
                val params = UriComponentsBuilder.fromUriString(url).build().queryParams

                params.getFirst("redirect_uri") shouldBe
                    "https://example.com/api/channel/channels/oauth/callback/github"
                stateSupport.verifyState(
                    requireNotNull(params.getFirst("state")),
                    AccountProvider.GITHUB,
                ).returnOrigin shouldBe
                    otherOrigin
            }

            it("허용되지 않은 Origin으로는 인증을 시작할 수 없다") {
                shouldThrow<ExpectedException> {
                    service.buildAuthorizeUrl(1L, 1L, AccountProvider.GITHUB, "https://evil.example.com")
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }
        }
    })
