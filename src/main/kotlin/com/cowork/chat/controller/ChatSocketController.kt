package com.cowork.chat.controller

import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.service.ChatService
import com.cowork.global.error.BusinessException
import com.cowork.global.error.ErrorResponse
import com.cowork.global.security.AuthUser
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import java.security.Principal

@Controller
class ChatSocketController(
    private val chatService: ChatService,
) {

    @MessageMapping("/channels/{channelId}/messages")
    fun send(
        @DestinationVariable channelId: Long,
        @Payload request: SendMessageRequest,
        principal: Principal,
    ) {
        val user = (principal as Authentication).principal as AuthUser
        chatService.send(channelId, user.id, request)
    }

    @MessageExceptionHandler(BusinessException::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleBusinessException(e: BusinessException): ErrorResponse = ErrorResponse(e.status.value(), e.message)
}
