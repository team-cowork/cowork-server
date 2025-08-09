package com.cowork.auth.controller

import com.cowork.auth.dto.RefreshTokenRequest
import com.cowork.auth.dto.TokenRequest
import com.cowork.auth.dto.TokenResponse
import com.cowork.auth.service.AuthService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "인증")
@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
) {

    @Operation(summary = "DataGSM 인가 코드로 토큰 발급", security = [])
    @PostMapping("/token")
    fun token(
        @Valid @RequestBody request: TokenRequest,
        @RequestHeader(HttpHeaders.USER_AGENT, required = false) userAgent: String?,
    ): TokenResponse = authService.login(request.code, request.codeVerifier, request.redirectUri, userAgent)

    @Operation(summary = "토큰 갱신", security = [])
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): TokenResponse =
        authService.refresh(request.refreshToken)

    @Operation(summary = "로그아웃")
    @PostMapping("/signout")
    fun signOut(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: RefreshTokenRequest,
    ) = authService.signOut(user.id, request.refreshToken)
}
