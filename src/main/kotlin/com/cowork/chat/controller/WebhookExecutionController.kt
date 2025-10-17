package com.cowork.chat.controller

import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.service.WebhookMessageService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class WebhookExecutionController(
    private val webhookMessageService: WebhookMessageService,
) {

    @PostMapping("/webhooks/{webhookId}/{token}")
    fun execute(
        @PathVariable webhookId: Long,
        @PathVariable token: String,
        @RequestBody request: WebhookExecuteRequest,
    ): MessageResponse = webhookMessageService.execute(webhookId, token, request.content, request.username)

    data class WebhookExecuteRequest(
        val content: String,
        val username: String? = null,
    )
}
