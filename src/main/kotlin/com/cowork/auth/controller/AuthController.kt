package com.cowork.auth.controller

import com.cowork.auth.dto.RefreshTokenRequest
import com.cowork.auth.dto.TokenRequest
import com.cowork.auth.dto.TokenResponse
import com.cowork.auth.service.AuthService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
) {

    @PostMapping("/token")
    fun token(
        @RequestBody request: TokenRequest,
        @RequestHeader(HttpHeaders.USER_AGENT, required = false) userAgent: String?,
    ): TokenResponse = authService.login(request.code, request.codeVerifier, request.redirectUri, userAgent)

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshTokenRequest): TokenResponse =
        authService.refresh(request.refreshToken)

    @PostMapping("/signout")
    fun signOut(
        @CurrentUser user: AuthUser,
        @RequestBody request: RefreshTokenRequest,
    ) = authService.signOut(user.id, request.refreshToken)
}
