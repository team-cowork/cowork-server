package com.cowork.channel.controller

import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.service.WebhookService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/channels/{channelId}/webhooks")
class WebhookController(
    private val webhookService: WebhookService,
) {

    @PostMapping
    fun createWebhook(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateWebhookRequest,
    ): ResponseEntity<WebhookResponse> =
        ResponseEntity.status(201).body(webhookService.createWebhook(userId, channelId, request))

    @GetMapping
    fun getWebhooks(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<List<WebhookResponse>> =
        ResponseEntity.ok(webhookService.getWebhooks(userId, channelId))

    @PatchMapping("/{webhookId}")
    fun updateWebhook(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable webhookId: Long,
        @RequestBody request: UpdateWebhookRequest,
    ): ResponseEntity<WebhookResponse> =
        ResponseEntity.ok(webhookService.updateWebhook(userId, channelId, webhookId, request))

    @DeleteMapping("/{webhookId}")
    fun deleteWebhook(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable webhookId: Long,
    ): ResponseEntity<Void> {
        webhookService.deleteWebhook(userId, channelId, webhookId)
        return ResponseEntity.noContent().build()
    }
}
