package com.cowork.channel.controller

import com.cowork.channel.dto.CreateMeetingNoteRequest
import com.cowork.channel.dto.MeetingNoteResponse
import com.cowork.channel.service.MeetingNoteService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "회의록", description = "회의록 작성 및 조회 API")
@RestController
@RequestMapping("/channels/{channelId}/meeting-notes")
class MeetingNoteController(
    private val meetingNoteService: MeetingNoteService,
) {

    @Operation(summary = "회의록 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @GetMapping
    fun listNotes(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<List<MeetingNoteResponse>> =
        ResponseEntity.ok(meetingNoteService.listNotes(userId, channelId))

    @Operation(summary = "회의록 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "회의록 없음"),
    )
    @GetMapping("/{noteId}")
    fun getNote(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable noteId: Long,
    ): ResponseEntity<MeetingNoteResponse> =
        ResponseEntity.ok(meetingNoteService.getNote(userId, channelId, noteId))

    @Operation(summary = "회의록 작성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "작성 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 섹션 id"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "템플릿 없음"),
    )
    @PostMapping
    fun createNote(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateMeetingNoteRequest,
    ): ResponseEntity<MeetingNoteResponse> =
        ResponseEntity.status(201).body(meetingNoteService.createNote(userId, channelId, request))
}
