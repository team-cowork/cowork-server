package com.cowork.channel.meetingnote.controller

import com.cowork.channel.meetingnote.dto.CreateMeetingNoteRequest
import com.cowork.channel.meetingnote.dto.MeetingNotePageResponse
import com.cowork.channel.meetingnote.dto.MeetingNoteResponse
import com.cowork.channel.meetingnote.service.MeetingNoteService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "회의록")
@RestController
@RequestMapping("/channels/{channelId}/meeting-notes")
class MeetingNoteController(
    private val meetingNoteService: MeetingNoteService,
) {

    @Operation(summary = "회의록 목록 조회")
    @GetMapping
    fun getNotes(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): MeetingNotePageResponse = meetingNoteService.getNotes(channelId, user.id, before, limit)

    @Operation(summary = "회의록 조회")
    @GetMapping("/{noteId}")
    fun getNote(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable noteId: Long,
    ): MeetingNoteResponse = meetingNoteService.getNote(channelId, noteId, user.id)

    @Operation(summary = "회의록 작성 (활성 템플릿 기준)")
    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestBody request: CreateMeetingNoteRequest,
    ): MeetingNoteResponse = meetingNoteService.create(channelId, user.id, request)
}
