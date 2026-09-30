package com.cowork.preference.security

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RequesterContextTest {

    @Test
    fun `X-User-Id와 X-User-Role이 유효하면 요청자 컨텍스트를 만든다`() {
        val ctx = requestWithHeaders(userId = "10", role = "MEMBER")

        val requester = RequesterContext.from(ctx)

        assertEquals(RequesterContext(userId = 10L, role = "MEMBER"), requester)
    }

    @Test
    fun `X-User-Id 헤더가 없으면 null을 반환한다`() {
        val ctx = requestWithHeaders(userId = null, role = "MEMBER")

        assertNull(RequesterContext.from(ctx))
    }

    @Test
    fun `X-User-Id가 숫자로 파싱되지 않으면 null을 반환한다`() {
        val ctx = requestWithHeaders(userId = "abc", role = "MEMBER")

        assertNull(RequesterContext.from(ctx))
    }

    @Test
    fun `X-User-Id가 0 이하이면 null을 반환한다`() {
        val ctx = requestWithHeaders(userId = "0", role = "MEMBER")

        assertNull(RequesterContext.from(ctx))
    }

    @Test
    fun `X-User-Role 헤더가 없으면 null을 반환한다`() {
        val ctx = requestWithHeaders(userId = "10", role = null)

        assertNull(RequesterContext.from(ctx))
    }

    @Test
    fun `X-User-Role이 ADMIN MEMBER가 아니면 null을 반환한다`() {
        val ctx = requestWithHeaders(userId = "10", role = "OWNER")

        assertNull(RequesterContext.from(ctx))
    }

    @Test
    fun `전역 ADMIN 역할이면 isGlobalAdmin이 true다`() {
        val requester = RequesterContext(userId = 10L, role = "ADMIN")

        assertEquals(true, requester.isGlobalAdmin)
    }

    @Test
    fun `MEMBER 역할이면 isGlobalAdmin이 false다`() {
        val requester = RequesterContext(userId = 10L, role = "MEMBER")

        assertEquals(false, requester.isGlobalAdmin)
    }

    private fun requestWithHeaders(userId: String?, role: String?): RoutingContext {
        val request = mockk<HttpServerRequest>()
        every { request.getHeader("X-User-Id") } returns userId
        every { request.getHeader("X-User-Role") } returns role
        val ctx = mockk<RoutingContext>()
        every { ctx.request() } returns request
        return ctx
    }
}
