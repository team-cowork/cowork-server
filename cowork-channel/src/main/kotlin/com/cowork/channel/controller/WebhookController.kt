package com.cowork.channel.controller

import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.service.WebhookService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "웹훅", description = "채널 웹훅 CRUD API (TEXT 타입 채널 전용)")
@RestController
@RequestMapping("/channels/{channelId}/webhooks")
class WebhookController(private val webhookService: WebhookService) {

    @Operation(summary = "웹훅 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "400", description = "TEXT 타입 채널이 아님"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createWebhook(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateWebhookRequest,
    ): WebhookResponse = webhookService.createWebhook(userId, channelId, request)

    @Operation(summary = "웹훅 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @GetMapping
    fun getWebhooks(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): List<WebhookResponse> = webhookService.getWebhooks(userId, channelId)

    @Operation(summary = "웹훅 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PatchMapping("/{webhookId}")
    fun updateWebhook(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable webhookId: Long,
        @RequestBody request: UpdateWebhookRequest,
    ): WebhookResponse = webhookService.updateWebhook(userId, channelId, webhookId, request)

    @Operation(summary = "웹훅 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @DeleteMapping("/{webhookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteWebhook(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable webhookId: Long,
    ) {
        webhookService.deleteWebhook(userId, channelId, webhookId)
    }
}
