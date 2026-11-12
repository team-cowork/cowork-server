package com.cowork.preference.handler

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext

class SettingDefinitionsHandlerTest :
    DescribeSpec({
        describe("설정 항목 목록 조회") {
            val handler = PreferenceHandler(mockk(), mockk(), mockk(), mockk(), mockk())

            it("로그인한 사용자는 개인 설정 조회 없이 공통 항목을 받는다") {
                val ctx = mockk<RoutingContext>()
                val request = mockk<HttpServerRequest>()
                val response = mockk<HttpServerResponse>(relaxed = true)
                val body = slot<String>()
                every { ctx.request() } returns request
                every { ctx.response() } returns response
                every { request.getHeader("X-User-Id") } returns "1"
                every { request.getHeader("X-User-Role") } returns "MEMBER"
                every { response.setStatusCode(any()) } returns response
                every { response.putHeader(any<String>(), any<String>()) } returns response
                every { response.end(capture(body)) } returns mockk()

                handler.getSettingDefinitions(ctx)

                verify { response.setStatusCode(200) }
                verify { response.putHeader("Content-Type", "application/json") }
                val catalog = JsonArray(body.captured)
                catalog.getJsonObject(0).getString("resourceType") shouldBe "ACCOUNT"
                catalog.getJsonObject(0).getJsonArray("settings").isEmpty shouldBe false

                handler.getRolePermissions(ctx)

                val permissions = JsonArray(body.captured)
                permissions.getJsonObject(0).getString("key") shouldBe "TEAM"
                permissions.getJsonObject(0).getJsonArray("permissions").isEmpty shouldBe false
            }

            it("요청자 정보가 없으면 항목을 반환하지 않는다") {
                val ctx = mockk<RoutingContext>()
                val request = mockk<HttpServerRequest>()
                val response = mockk<HttpServerResponse>(relaxed = true)
                every { ctx.request() } returns request
                every { ctx.response() } returns response
                every { request.getHeader("X-User-Id") } returns null
                every { response.setStatusCode(any()) } returns response

                handler.getSettingDefinitions(ctx)
                handler.getRolePermissions(ctx)

                verify { response.setStatusCode(400) }
                verify(exactly = 0) { response.setStatusCode(200) }
            }
        }
    })
