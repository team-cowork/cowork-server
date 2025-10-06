package com.cowork.chat.controller

import com.cowork.chat.dto.EditMessageRequest
import com.cowork.chat.dto.MessagePageResponse
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.service.ChatService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/channels/{channelId}")
class ChatController(
    private val chatService: ChatService,
) {

    @PostMapping("/messages")
    fun send(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestBody request: SendMessageRequest,
    ): MessageResponse = chatService.send(channelId, user.id, request)

    @GetMapping("/messages")
    fun getMessages(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): MessagePageResponse = chatService.getMessages(channelId, user.id, before, limit)
}
