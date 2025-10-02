package com.cowork.channel.controller

import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.service.WebhookService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "웹훅")
@RestController
@RequestMapping("/channels/{channelId}/webhooks")
class WebhookController(
    private val webhookService: WebhookService,
) {

    @Operation(summary = "웹훅 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: CreateWebhookRequest,
    ): WebhookResponse = webhookService.create(channelId, user.id, request)

    @Operation(summary = "웹훅 목록 조회")
    @GetMapping
    fun getWebhooks(@CurrentUser user: AuthUser, @PathVariable channelId: Long): List<WebhookResponse> =
        webhookService.getWebhooks(channelId, user.id)

    @Operation(summary = "웹훅 수정")
    @PatchMapping("/{webhookId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable webhookId: Long,
        @Valid @RequestBody request: UpdateWebhookRequest,
    ): WebhookResponse = webhookService.update(channelId, webhookId, user.id, request)

    @Operation(summary = "웹훅 삭제")
    @DeleteMapping("/{webhookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable webhookId: Long,
    ) = webhookService.delete(channelId, webhookId, user.id)
}
