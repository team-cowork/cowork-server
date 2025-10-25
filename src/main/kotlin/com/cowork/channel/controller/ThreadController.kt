package com.cowork.channel.controller

import com.cowork.channel.dto.CreateThreadRequest
import com.cowork.channel.dto.ThreadResponse
import com.cowork.channel.dto.UpdateThreadRequest
import com.cowork.channel.service.ThreadService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/channels/{channelId}/threads")
class ThreadController(
    private val threadService: ThreadService,
) {

    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestBody request: CreateThreadRequest,
    ): ThreadResponse = threadService.create(channelId, user.id, request)
}
