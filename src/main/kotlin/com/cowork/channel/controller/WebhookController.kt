package com.cowork.channel.controller

import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.service.WebhookService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/channels/{channelId}/webhooks")
class WebhookController(
    private val webhookService: WebhookService,
) {

    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestBody request: CreateWebhookRequest,
    ): WebhookResponse = webhookService.create(channelId, user.id, request)
}
