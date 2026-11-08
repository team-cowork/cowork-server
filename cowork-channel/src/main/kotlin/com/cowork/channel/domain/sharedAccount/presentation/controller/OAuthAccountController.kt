package com.cowork.channel.domain.sharedAccount.presentation.controller

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.service.BuildOAuthAuthorizeUrlService
import com.cowork.channel.domain.sharedAccount.service.support.OAuthCallbackRedirectSupport
import com.cowork.channel.domain.sharedAccount.service.support.OAuthStateSupport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "계정 공유 OAuth", description = "공유 계정 OAuth 연동 API")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [
            Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonApiResponse::class),
                examples = [
                    ExampleObject(
                        value = """{"status":"401 UNAUTHORIZED","code":401,"message":"Unauthorized"}""",
                    ),
                ],
            ),
        ],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
class OAuthAccountController(
    private val buildOAuthAuthorizeUrlService: BuildOAuthAuthorizeUrlService,
    private val oAuthCallbackRedirectSupport: OAuthCallbackRedirectSupport,
    private val oAuthStateSupport: OAuthStateSupport,
) {

    @Operation(
        summary = "OAuth 인증 URL 발급",
        description = "응답의 redirectUrl로 이동해 인증을 시작합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "인증 URL 반환"),
        ApiResponse(
            responseCode = "400",
            description = "지원하지 않는 인증 제공자 또는 허용되지 않은 복귀 Origin",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"400 BAD_REQUEST","code":400,"message":"요청 값이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "팀 멤버가 아님",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"404 NOT_FOUND","code":404,"message":"요청한 대상을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @GetMapping("/channels/{channelId}/accounts/oauth/authorize/{provider}")
    fun authorize(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable provider: String,
        @Parameter(description = "인증 후 돌아갈 웹 Origin입니다. 생략하면 허용 목록의 첫 번째 주소를 사용합니다.")
        @RequestParam(name = "return_origin", required = false) returnOrigin: String?,
    ): ResponseEntity<Map<String, String>> {
        val accountProvider = runCatching { AccountProvider.valueOf(provider.uppercase()) }.getOrElse {
            return ResponseEntity.badRequest().body(mapOf("error" to "지원하지 않는 provider: $provider"))
        }
        val request = buildOAuthAuthorizeUrlService.buildAuthorizeUrl(
            channelId,
            userId,
            accountProvider,
            returnOrigin,
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, oAuthStateSupport.nonceCookie(accountProvider, request.nonce).toString())
            .body(mapOf("redirectUrl" to request.redirectUrl))
    }

    // OAuth provider가 브라우저를 직접 리다이렉트하는 콜백 — Gateway 인증 불필요
    @Operation(summary = "OAuth 콜백 처리 (OAuth provider → 서버 → 클라이언트 리다이렉트)", hidden = true)
    @GetMapping("/channels/oauth/callback/{provider}")
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam state: String,
        @RequestParam(required = false) error: String?,
        @CookieValue(name = OAuthStateSupport.NONCE_COOKIE_NAME, required = false) browserNonce: String?,
    ): ResponseEntity<Void> {
        // resolveRedirect가 provider 이름을 먼저 검증하므로 아래 valueOf는 실패하지 않는다.
        val location = oAuthCallbackRedirectSupport.resolveRedirect(provider, code, state, error, browserNonce)
        val expiredCookie = oAuthStateSupport.expiredNonceCookie(AccountProvider.valueOf(provider.uppercase()))
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
            .location(location)
            .build()
    }
}
