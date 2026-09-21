package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.service.HandleOAuthCallbackService
import com.cowork.channel.global.config.OAuthProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper

class OAuthCallbackRedirectSupportTest :
    DescribeSpec({
        val defaultOrigin = "https://web.example.com"
        val returnOrigin = "https://admin.example.com"
        val stateSupport = OAuthStateSupport(
            OAuthProperties(
                allowedReturnOrigins = listOf(defaultOrigin, returnOrigin),
                stateSecret = "test-state-secret",
            ),
            jacksonObjectMapper(),
        )
        lateinit var callbackService: HandleOAuthCallbackService
        lateinit var support: OAuthCallbackRedirectSupport

        beforeEach {
            callbackService = mockk()
            support = OAuthCallbackRedirectSupport(stateSupport, callbackService)
        }

        describe("OAuth 콜백 복귀 주소 결정은") {
            it("성공하면 state에 저장한 프런트의 채널 페이지로 복귀한다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB, returnOrigin)
                every { callbackService.handleCallback("github", "code", state) } returns SharedAccount(
                    id = 10L,
                    channelId = 1L,
                    provider = AccountProvider.GITHUB,
                    providerLabel = null,
                    accountIdentifier = "ghuser",
                    credential = null,
                    connectedViaOAuth = true,
                    createdBy = 7L,
                )

                support.resolveRedirect("github", "code", state, null).toString() shouldBe
                    "$returnOrigin/channels/1?newAccountId=10"
            }

            it("계정 연동 처리에 실패하면 선택한 프런트의 오류 페이지로 복귀한다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB, returnOrigin)
                every { callbackService.handleCallback("github", "code", state) } throws
                    ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

                support.resolveRedirect("github", "code", state, null).toString() shouldBe
                    "$returnOrigin/error?message=oauth_failed"
            }

            it("사용자가 연동을 취소하면 토큰을 교환하지 않고 선택한 프런트로 복귀한다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB, returnOrigin)

                support.resolveRedirect("github", null, state, "access_denied").toString() shouldBe
                    "$returnOrigin/error?message=oauth_failed"
                verify(exactly = 0) { callbackService.handleCallback(any(), any(), any()) }
            }

            it("code와 error가 함께 오면 오류로 처리한다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB, returnOrigin)

                support.resolveRedirect("github", "code", state, "access_denied").toString() shouldBe
                    "$returnOrigin/error?message=oauth_failed"
                verify(exactly = 0) { callbackService.handleCallback(any(), any(), any()) }
            }

            it("code가 누락되면 선택한 프런트의 오류 페이지로 복귀한다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB, returnOrigin)

                support.resolveRedirect("github", null, state, null).toString() shouldBe
                    "$returnOrigin/error?message=oauth_failed"
                verify(exactly = 0) { callbackService.handleCallback(any(), any(), any()) }
            }

            it("복귀 Origin을 생략한 요청의 취소는 기본 웹 주소로 돌아간다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB)

                support.resolveRedirect("github", null, state, "access_denied").toString() shouldBe
                    "$defaultOrigin/error?message=oauth_failed"
            }

            it("변조된 state이면 오류 콜백도 리다이렉트하지 않고 거절한다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB, returnOrigin)
                val tampered = "${state.substringBefore('.')}.invalid"

                shouldThrow<ExpectedException> {
                    support.resolveRedirect("github", null, tampered, "access_denied")
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
                verify(exactly = 0) { callbackService.handleCallback(any(), any(), any()) }
            }

            it("provider가 다른 state로는 리다이렉트하지 않는다") {
                val state = stateSupport.buildState(1L, 7L, AccountProvider.GITHUB, returnOrigin)

                shouldThrow<ExpectedException> {
                    support.resolveRedirect("notion", "code", state, null)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
                verify(exactly = 0) { callbackService.handleCallback(any(), any(), any()) }
            }

            it("지원하지 않는 provider는 거절한다") {
                shouldThrow<ExpectedException> {
                    support.resolveRedirect("unknown", "code", "state", null)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
                verify(exactly = 0) { callbackService.handleCallback(any(), any(), any()) }
            }
        }
    })
