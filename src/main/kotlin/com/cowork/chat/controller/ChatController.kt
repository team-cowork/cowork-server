package com.cowork.chat.controller

import com.cowork.chat.dto.EditMessageRequest
import com.cowork.chat.dto.MessagePageResponse
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.service.ChatService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(name = "채팅")
@RestController
@RequestMapping("/channels/{channelId}")
class ChatController(
    private val chatService: ChatService,
) {

    @Operation(summary = "메시지 전송")
    @PostMapping("/messages")
    fun send(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestBody request: SendMessageRequest,
    ): MessageResponse = chatService.send(channelId, user.id, request)

    @Operation(summary = "메시지 목록 조회 (커서 기반)")
    @GetMapping("/messages")
    fun getMessages(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): MessagePageResponse = chatService.getMessages(channelId, user.id, before, limit)

    @Operation(summary = "메시지 수정")
    @PatchMapping("/messages/{messageId}")
    fun edit(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable messageId: Long,
        @RequestBody request: EditMessageRequest,
    ): MessageResponse = chatService.edit(channelId, messageId, user.id, request.content)

    @Operation(summary = "메시지 삭제")
    @DeleteMapping("/messages/{messageId}")
    fun delete(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable messageId: Long,
    ) = chatService.delete(channelId, messageId, user.id)
}
