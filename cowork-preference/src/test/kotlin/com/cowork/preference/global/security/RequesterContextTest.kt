package com.cowork.preference.global.security

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext

private fun requestWithHeaders(userId: String?): RoutingContext {
    val request = mockk<HttpServerRequest>()
    every { request.getHeader("X-User-Id") } returns userId
    val ctx = mockk<RoutingContext>()
    every { ctx.request() } returns request
    return ctx
}

class RequesterContextTest :
    DescribeSpec({
        describe("RequesterContext 클래스의") {
            describe("from 메서드는") {
                context("X-User-Id가 유효하면") {
                    it("요청자 컨텍스트를 만든다") {
                        val ctx = requestWithHeaders(userId = "10")

                        val requester = RequesterContext.from(ctx)

                        requester shouldBe RequesterContext(userId = 10L)
                    }
                }

                context("X-User-Id 헤더가 없으면") {
                    it("null을 반환한다") {
                        val ctx = requestWithHeaders(userId = null)

                        RequesterContext.from(ctx).shouldBeNull()
                    }
                }

                context("X-User-Id가 숫자로 파싱되지 않으면") {
                    it("null을 반환한다") {
                        val ctx = requestWithHeaders(userId = "abc")

                        RequesterContext.from(ctx).shouldBeNull()
                    }
                }

                context("X-User-Id가 0 이하이면") {
                    it("null을 반환한다") {
                        val ctx = requestWithHeaders(userId = "0")

                        RequesterContext.from(ctx).shouldBeNull()
                    }
                }
            }
        }
    })
