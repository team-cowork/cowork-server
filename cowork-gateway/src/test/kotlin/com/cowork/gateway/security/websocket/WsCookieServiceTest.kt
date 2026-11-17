package com.cowork.gateway.security.websocket

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.time.Duration
import java.time.Instant

class WsCookieServiceTest :
    DescribeSpec({
        val service = WsCookieService()
        val now = Instant.parse("2026-11-17T05:00:00.500Z")

        fun authentication(expiresAt: Long? = now.epochSecond + 600, token: String? = "verified.access.token") =
            UsernamePasswordAuthenticationToken("42", token, emptyList()).also {
                it.details =
                    expiresAt?.let { expiry -> mapOf("expiresAt" to expiry.toString()) } ?: emptyMap<String, String>()
            }

        describe("WsCookieService 클래스의 createCookie 메서드는") {
            context("검증된 토큰과 남은 유효시간이 있으면") {
                it("같은 토큰을 재사용하고 남은 초를 내림하여 쿠키 수명을 제한한다") {
                    val cookie = service.createCookie(authentication(), now)

                    cookie shouldNotBe null
                    cookie!!.name shouldBe "cowork_ws_token"
                    cookie.value shouldBe "verified.access.token"
                    cookie.maxAge shouldBe Duration.ofSeconds(599)
                    cookie.path shouldBe "/ws"
                    cookie.domain shouldBe null
                    cookie.isHttpOnly shouldBe true
                    cookie.isSecure shouldBe true
                    cookie.sameSite shouldBe "None"
                }
            }

            context("만료 시각이 없거나 남은 유효시간이 1초 미만이면") {
                it("쿠키를 발급하지 않는다") {
                    listOf(null, now.epochSecond - 1, now.epochSecond, now.epochSecond + 1).forEach { expiresAt ->
                        service.createCookie(authentication(expiresAt), now) shouldBe null
                    }
                }
            }

            context("인증되지 않았거나 토큰을 사용할 수 없으면") {
                it("쿠키를 발급하지 않는다") {
                    service.createCookie(UsernamePasswordAuthenticationToken("42", "unverified"), now) shouldBe null
                    service.createCookie(authentication(token = null), now) shouldBe null
                    service.createCookie(authentication(token = " "), now) shouldBe null
                }
            }
        }

        describe("WsCookieService 클래스의 clearCookie 메서드는") {
            it("같은 이름과 경로의 쿠키를 즉시 만료시킨다") {
                val cookie = service.clearCookie()

                cookie.name shouldBe "cowork_ws_token"
                cookie.value shouldBe ""
                cookie.path shouldBe "/ws"
                cookie.domain shouldBe null
                cookie.maxAge shouldBe Duration.ZERO
                cookie.isHttpOnly shouldBe true
                cookie.isSecure shouldBe true
                cookie.sameSite shouldBe "None"
            }
        }
    })
