package com.cowork.channel.controller

import com.cowork.channel.dto.*
import com.cowork.channel.service.ChannelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "채널", description = "채널 생성/수정/삭제 및 멤버 관리 API")
@RestController
@RequestMapping("/channels")
class ChannelController(
    private val channelService: ChannelService,
) {

    @Operation(summary = "채널 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @PostMapping
    fun createChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateChannelRequest,
    ): ResponseEntity<ChannelResponse> =
        ResponseEntity.status(201).body(channelService.createChannel(userId, request))

    @Operation(summary = "채널 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "채널 없음"),
    )
    @GetMapping("/{channelId}")
    fun getChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<ChannelResponse> =
        ResponseEntity.ok(channelService.getChannel(userId, channelId))

    @Operation(summary = "채널 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PatchMapping("/{channelId}")
    fun updateChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: UpdateChannelRequest,
    ): ResponseEntity<ChannelResponse> =
        ResponseEntity.ok(channelService.updateChannel(userId, channelId, request))

    @Operation(summary = "채널 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @DeleteMapping("/{channelId}")
    fun deleteChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<Void> {
        channelService.deleteChannel(userId, channelId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "채널 멤버 추가", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "추가 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PostMapping("/{channelId}/members")
    fun addMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: AddMemberRequest,
    ): ResponseEntity<ChannelMemberResponse> =
        ResponseEntity.status(201).body(channelService.addMember(userId, channelId, request))

    @Operation(summary = "채널 멤버 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @GetMapping("/{channelId}/members")
    fun getMembers(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<List<ChannelMemberResponse>> =
        ResponseEntity.ok(channelService.getMembers(userId, channelId))

    @Operation(summary = "채널 멤버 제거", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @DeleteMapping("/{channelId}/members/{memberId}")
    fun removeMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable memberId: Long,
    ): ResponseEntity<Void> {
        channelService.removeMember(userId, channelId, memberId)
        return ResponseEntity.noContent().build()
    }
}
