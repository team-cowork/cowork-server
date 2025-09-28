package com.cowork.auth

import com.cowork.auth.controller.AuthController
import com.cowork.auth.dto.TokenResponse
import com.cowork.auth.service.AuthService
import com.cowork.global.error.BusinessException
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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

    @Test
    fun `만료된 리프레시 토큰은 401을 반환한다`() {
        every { authService.refresh("old") } throws BusinessException(HttpStatus.UNAUTHORIZED, "만료된 리프레시 토큰입니다.")

        mockMvc.post("/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"old"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `로그아웃하면 204를 반환한다`() {
        every { authService.signOut(1, "refresh") } just runs

        mockMvc.post("/auth/signout") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"refresh"}"""
        }.andExpect { status { isNoContent() } }

        verify { authService.signOut(1, "refresh") }
    }
}
