package com.cowork.channel.domain.sharedAccount.presentation.controller

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.service.BuildOAuthAuthorizeUrlService
import com.cowork.channel.domain.sharedAccount.service.support.OAuthCallbackRedirectSupport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "계정 공유 OAuth", description = "공유 계정 OAuth 연동 API")
@RestController
class OAuthAccountController(
    private val buildOAuthAuthorizeUrlService: BuildOAuthAuthorizeUrlService,
    private val oAuthCallbackRedirectSupport: OAuthCallbackRedirectSupport,
) {

    @Operation(
        summary = "OAuth 인증 시작 — 해당 provider의 authorize URL 반환",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "redirect URL 반환"),
        ApiResponse(responseCode = "400", description = "OAuth 미지원 provider 또는 허용되지 않은 복귀 Origin"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "채널 없음"),
    )
    @GetMapping("/channels/{channelId}/accounts/oauth/authorize/{provider}")
    fun authorize(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable provider: String,
        @Parameter(description = "OAuth 완료 후 복귀할 웹 Origin. 생략하면 허용 목록의 첫 번째 주소 사용")
        @RequestParam(name = "return_origin", required = false) returnOrigin: String?,
    ): ResponseEntity<Map<String, String>> {
        val accountProvider = runCatching { AccountProvider.valueOf(provider.uppercase()) }.getOrElse {
            return ResponseEntity.badRequest().body(mapOf("error" to "지원하지 않는 provider: $provider"))
        }
        val redirectUrl = buildOAuthAuthorizeUrlService.buildAuthorizeUrl(
            channelId,
            userId,
            accountProvider,
            returnOrigin,
        )
        return ResponseEntity.ok(mapOf("redirectUrl" to redirectUrl))
    }

    // OAuth provider가 브라우저를 직접 리다이렉트하는 콜백 — Gateway 인증 불필요
    @Operation(summary = "OAuth 콜백 처리 (OAuth provider → 서버 → 클라이언트 리다이렉트)", hidden = true)
    @GetMapping("/channels/oauth/callback/{provider}")
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam state: String,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FOUND)
        .location(oAuthCallbackRedirectSupport.resolveRedirect(provider, code, state, error))
        .build()
}
