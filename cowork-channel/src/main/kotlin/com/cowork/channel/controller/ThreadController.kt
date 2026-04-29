package com.cowork.channel.controller

import com.cowork.channel.dto.CreateThreadRequest
import com.cowork.channel.dto.ThreadResponse
import com.cowork.channel.dto.UpdateThreadRequest
import com.cowork.channel.service.ThreadService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/channels/{channelId}/threads")
class ThreadController(
    private val threadService: ThreadService,
) {

    @PostMapping
    fun createThread(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateThreadRequest,
    ): ResponseEntity<ThreadResponse> =
        ResponseEntity.status(201).body(threadService.createThread(userId, channelId, request))

    @GetMapping
    fun getThreads(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<ThreadResponse>> =
        ResponseEntity.ok(threadService.getThreads(userId, channelId, includeArchived, pageable))

    @PatchMapping("/{threadId}")
    fun updateThread(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable threadId: Long,
        @RequestBody request: UpdateThreadRequest,
    ): ResponseEntity<ThreadResponse> =
        ResponseEntity.ok(threadService.updateThread(userId, channelId, threadId, request))
}
