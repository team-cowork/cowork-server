package com.cowork.gateway.security.websocket

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "WebSocket 인증")
@ApiResponse(
    responseCode = "403",
    description = "허용하지 않은 출처",
    content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
)
@RestController
@RequestMapping("/api/auth/ws-cookie")
class WsCookieController(private val service: WsCookieService) {
    @Operation(
        summary = "WebSocket 인증 쿠키 설정",
        description = "소켓 연결 전과 액세스 토큰 갱신 후 브라우저에서 credentials: include로 직접 호출합니다. " +
            "쿠키 수명은 액세스 토큰의 남은 유효시간으로 제한됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "204",
            description = "설정 성공",
            content = [Content()],
            headers = [
                Header(
                    name = "Set-Cookie",
                    required = true,
                    schema = Schema(
                        type = "string",
                        example = "cowork_ws_token=<access_token>; Path=/ws; Max-Age=300; " +
                            "Secure; HttpOnly; SameSite=None",
                    ),
                ),
                Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"])),
            ],
        ),
        ApiResponse(
            responseCode = "401",
            description = "유효한 토큰 없음 또는 남은 유효시간 1초 미만",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
    )
    @PostMapping
    fun create(@Parameter(hidden = true) authentication: Authentication): ResponseEntity<Void> {
        val cookie = service.createCookie(authentication)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build()
        return respond(cookie)
    }

    @Operation(
        summary = "WebSocket 인증 쿠키 삭제",
        description = "로그아웃 시 브라우저에서 credentials: include로 직접 호출합니다. " +
            "토큰 폐기와 소켓 연결 종료는 별도로 처리합니다.",
    )
    @ApiResponse(
        responseCode = "204",
        description = "삭제 성공",
        content = [Content()],
        headers = [
            Header(
                name = "Set-Cookie",
                required = true,
                schema = Schema(
                    type = "string",
                    example = "cowork_ws_token=; Path=/ws; Max-Age=0; Secure; HttpOnly; SameSite=None",
                ),
            ),
            Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"])),
        ],
    )
    @DeleteMapping
    fun delete(): ResponseEntity<Void> = respond(service.clearCookie())

    private fun respond(cookie: ResponseCookie): ResponseEntity<Void> = ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .cacheControl(CacheControl.noStore())
        .build()
}
