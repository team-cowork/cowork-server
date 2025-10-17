package com.cowork.chat.controller

import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.service.WebhookMessageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "웹훅")
@RestController
class WebhookExecutionController(
    private val webhookMessageService: WebhookMessageService,
) {

    @Operation(summary = "웹훅으로 메시지 전송", security = [])
    @PostMapping("/webhooks/{webhookId}/{token}")
    @ResponseStatus(HttpStatus.CREATED)
    fun execute(
        @PathVariable webhookId: Long,
        @PathVariable token: String,
        @Valid @RequestBody request: WebhookExecuteRequest,
    ): MessageResponse = webhookMessageService.execute(webhookId, token, request.content, request.username)

    data class WebhookExecuteRequest(
        @field:NotBlank
        @field:Size(max = 4000)
        val content: String,
        @field:Size(max = 100)
        val username: String? = null,
    )
}
