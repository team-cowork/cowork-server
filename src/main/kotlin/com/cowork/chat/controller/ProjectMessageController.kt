package com.cowork.chat.controller

import com.cowork.chat.dto.MessagePageResponse
import com.cowork.chat.service.ChatService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ProjectMessageController(
    private val chatService: ChatService,
) {

    @GetMapping("/projects/{projectId}/messages/search")
    fun search(
        @CurrentUser user: AuthUser,
        @PathVariable projectId: Long,
        @RequestParam keyword: String,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): MessagePageResponse = chatService.searchProjectMessages(projectId, user.id, keyword, before, limit)
}
