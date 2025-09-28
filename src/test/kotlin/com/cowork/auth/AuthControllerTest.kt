package com.cowork.auth

import com.cowork.auth.controller.AuthController
import com.cowork.auth.dto.TokenResponse
import com.cowork.auth.service.AuthService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post

class AuthControllerTest : ControllerTestSupport() {

    private val authService = mockk<AuthService>()
    private val mockMvc = mockMvc(AuthController(authService))

    @Test
    fun `로그인하면 User-Agent를 기기 정보로 넘긴다`() {
        every { authService.login("code", "verifier", "https://cowork.team/callback", "Chrome") } returns
            TokenResponse(accessToken = "access", refreshToken = "refresh", expiresIn = 1800)

        mockMvc.post("/auth/token") {
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.USER_AGENT, "Chrome")
            content = """{"code":"code","codeVerifier":"verifier","redirectUri":"https://cowork.team/callback"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresIn") { value(1800) }
        }
    }

    @Test
    fun `PKCE 검증 값이 없으면 400을 반환한다`() {
        mockMvc.post("/auth/token") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"code","redirectUri":"https://cowork.team/callback"}"""
        }.andExpect { status { isBadRequest() } }
    }
}
