package com.cowork.channel.controller

import com.cowork.channel.config.OAuthProperties
import com.cowork.channel.domain.AccountProvider
import com.cowork.channel.service.OAuthAccountService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

@Tag(name = "계정 공유 OAuth", description = "공유 계정 OAuth 연동 API")
@RestController
class OAuthAccountController(
    private val oAuthAccountService: OAuthAccountService,
    private val oAuthProperties: OAuthProperties,
) {

    @Operation(
        summary = "OAuth 인증 시작 — 해당 provider의 authorize URL 반환",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "redirect URL 반환"),
        ApiResponse(responseCode = "400", description = "OAuth를 지원하지 않는 provider"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "채널 없음"),
    )
    @GetMapping("/channels/{channelId}/accounts/oauth/authorize/{provider}")
    fun authorize(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable provider: String,
    ): ResponseEntity<Map<String, String>> {
        val accountProvider = runCatching { AccountProvider.valueOf(provider.uppercase()) }.getOrElse {
            return ResponseEntity.badRequest().body(mapOf("error" to "지원하지 않는 provider: $provider"))
        }
        val redirectUrl = oAuthAccountService.buildAuthorizeUrl(channelId, userId, accountProvider)
        return ResponseEntity.ok(mapOf("redirectUrl" to redirectUrl))
    }

    // OAuth provider가 브라우저를 직접 리다이렉트하는 콜백 — Gateway 인증 불필요
    @Operation(summary = "OAuth 콜백 처리 (OAuth provider → 서버 → 클라이언트 리다이렉트)", hidden = true)
    @GetMapping("/channels/oauth/callback/{provider}")
    fun callback(
        @PathVariable provider: String,
        @RequestParam code: String,
        @RequestParam state: String,
    ): ResponseEntity<Void> = runCatching {
        val account = oAuthAccountService.handleCallback(provider, code, state)
        val location = "${oAuthProperties.clientRedirectUrl}/channels/${account.channelId}?newAccountId=${account.id}"
        ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(location))
            .build<Void>()
    }.getOrElse { ex ->
        val errorUrl = "${oAuthProperties.clientRedirectUrl}/error?message=oauth_failed"
        ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(errorUrl))
            .build()
    }
}
