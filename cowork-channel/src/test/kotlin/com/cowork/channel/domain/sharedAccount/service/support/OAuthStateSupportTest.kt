package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.global.config.OAuthProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class OAuthStateSupportTest :
    DescribeSpec({
        val defaultOrigin = "https://web.example.com"
        val otherOrigin = "https://admin.example.com"
        val properties = OAuthProperties(
            allowedReturnOrigins = listOf(defaultOrigin, otherOrigin),
            stateSecret = "test-state-secret",
        )
        val support = OAuthStateSupport(properties, jacksonObjectMapper())

        describe("OAuth state의 복귀 Origin 검증은") {
            it("허용된 두 번째 Origin을 서명된 state에 보관한다") {
                val state = support.buildState(1L, 7L, AccountProvider.GITHUB, otherOrigin)

                support.verifyState(state, AccountProvider.GITHUB) shouldBe OAuthState(1L, 7L, otherOrigin)
            }

            it("Origin을 생략하면 목록의 첫 번째 웹 주소를 사용한다") {
                val state = support.buildState(1L, 7L, AccountProvider.GITHUB)

                support.verifyState(state, AccountProvider.GITHUB).returnOrigin shouldBe defaultOrigin
            }

            it("원소가 하나인 목록도 같은 방식으로 선택한다") {
                val singleOrigin =
                    OAuthStateSupport(
                        properties.copy(allowedReturnOrigins = listOf(otherOrigin)),
                        jacksonObjectMapper(),
                    )

                val state = singleOrigin.buildState(1L, 7L, AccountProvider.GITHUB)

                singleOrigin.verifyState(state, AccountProvider.GITHUB).returnOrigin shouldBe otherOrigin
            }

            it("목록 순서가 바뀌면 새 첫 번째 주소를 사용한다") {
                val reordered = OAuthStateSupport(
                    properties.copy(allowedReturnOrigins = listOf(otherOrigin, defaultOrigin)),
                    jacksonObjectMapper(),
                )
                val state = reordered.buildState(1L, 7L, AccountProvider.GITHUB)

                reordered.verifyState(state, AccountProvider.GITHUB).returnOrigin shouldBe otherOrigin
            }

            it("허용 목록이 비어 있으면 초기화를 거절한다") {
                shouldThrow<IllegalArgumentException> {
                    OAuthStateSupport(properties.copy(allowedReturnOrigins = emptyList()), jacksonObjectMapper())
                }
            }

            listOf(
                "https://evil.example.com",
                "$defaultOrigin.evil.example.com",
                "https://web.example.com:8443",
            ).forEach { origin ->
                it("허용 목록과 정확히 일치하지 않는 $origin 주소를 거절한다") {
                    shouldThrow<ExpectedException> {
                        support.buildState(1L, 7L, AccountProvider.GITHUB, origin)
                    }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            listOf(
                "", " ", "//web.example.com", "javascript:alert(1)",
                "$defaultOrigin/", "$defaultOrigin/path", "$defaultOrigin?next=evil", "$defaultOrigin#fragment",
                "https://web.example.com@evil.example.com", "https://web.example.com\\@evil.example.com",
            ).forEach { origin ->
                it("설정에 포함됐더라도 Origin 형식이 아닌 '$origin' 값은 거절한다") {
                    val invalidConfig =
                        OAuthStateSupport(properties.copy(allowedReturnOrigins = listOf(origin)), jacksonObjectMapper())

                    shouldThrow<ExpectedException> {
                        invalidConfig.buildState(1L, 7L, AccountProvider.GITHUB, origin)
                    }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            it("서명 후 복귀 Origin만 바꾼 state를 거절한다") {
                val original = support.buildState(1L, 7L, AccountProvider.GITHUB, defaultOrigin)
                val (payload, signature) = original.split('.')
                val changedJson = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
                    .replace(defaultOrigin, otherOrigin)
                val changedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    changedJson.toByteArray(Charsets.UTF_8),
                )

                shouldThrow<ExpectedException> {
                    support.verifyState("$changedPayload.$signature", AccountProvider.GITHUB)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("시작 후 허용 목록에서 제거된 Origin은 콜백에서도 거절한다") {
                val state = support.buildState(1L, 7L, AccountProvider.GITHUB, otherOrigin)
                val restricted =
                    OAuthStateSupport(
                        properties.copy(allowedReturnOrigins = listOf(defaultOrigin)),
                        jacksonObjectMapper(),
                    )

                shouldThrow<ExpectedException> {
                    restricted.verifyState(state, AccountProvider.GITHUB)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("Origin 필드가 없는 기존 state는 목록의 첫 번째 웹 주소로 복귀한다") {
                val state = signOAuthState(properties.stateSecret, emptyMap())

                support.verifyState(state, AccountProvider.GITHUB).returnOrigin shouldBe defaultOrigin
            }

            it("Origin 필드가 있으나 문자열이 아니면 기본 주소로 대체하지 않고 거절한다") {
                val state = signOAuthState(properties.stateSecret, mapOf("returnOrigin" to null))

                shouldThrow<ExpectedException> {
                    support.verifyState(state, AccountProvider.GITHUB)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("만료된 state를 거절한다") {
                val state = signOAuthState(properties.stateSecret, mapOf("exp" to Instant.now().epochSecond - 1))

                shouldThrow<ExpectedException> {
                    support.verifyState(state, AccountProvider.GITHUB)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("다른 provider의 콜백에 전달한 state를 거절한다") {
                val state = support.buildState(1L, 7L, AccountProvider.GITHUB, otherOrigin)

                shouldThrow<ExpectedException> {
                    support.verifyState(state, AccountProvider.NOTION)
                }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }
        }

        describe("providerConfigOf 메서드는") {
            it("설정하지 않은 선택형 provider에 SERVICE_UNAVAILABLE을 반환한다") {
                shouldThrow<ExpectedException> {
                    support.providerConfigOf(AccountProvider.GITHUB)
                }.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            }
        }
    })

private fun signOAuthState(secret: String, overrides: Map<String, Any?>): String {
    val payload = mapOf(
        "channelId" to 1L,
        "userId" to 7L,
        "provider" to AccountProvider.GITHUB.name,
        "nonce" to "test-nonce",
        "exp" to Instant.now().epochSecond + 300,
    ) + overrides
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val payloadB64 = encoder.encodeToString(jacksonObjectMapper().writeValueAsBytes(payload))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val signature = encoder.encodeToString(mac.doFinal(payloadB64.toByteArray(Charsets.UTF_8)))
    return "$payloadB64.$signature"
}
