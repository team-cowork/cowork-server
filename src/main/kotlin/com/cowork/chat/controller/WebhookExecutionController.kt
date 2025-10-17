package com.cowork.chat.controller

import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.service.WebhookMessageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "웹훅")
@RestController
class WebhookExecutionController(
    private val webhookMessageService: WebhookMessageService,
) {

    @Operation(summary = "웹훅으로 메시지 전송", security = [])
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
