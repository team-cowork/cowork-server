package com.cowork.channel.domain.channel.presentation.controller

import com.cowork.channel.domain.channel.presentation.data.request.AddMemberRequest
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelMemberResponse
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.ChannelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "채널", description = "채널 생성/수정/삭제 및 멤버 관리 API")
@RestController
@RequestMapping("/channels")
class ChannelController(private val channelService: ChannelService, private val objectMapper: ObjectMapper) {

    @Operation(summary = "채널 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateChannelRequest,
    ): ChannelResponse = channelService.createChannel(userId, request)

    @Operation(summary = "채널 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "채널 없음"),
    )
    @GetMapping("/{channelId}")
    fun getChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ChannelResponse = channelService.getChannel(userId, channelId)

    @Operation(summary = "채널 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PatchMapping("/{channelId}")
    fun updateChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @SwaggerRequestBody(content = [Content(schema = Schema(implementation = UpdateChannelRequest::class))])
        @RequestBody body: JsonNode,
    ): ChannelResponse {
        val request = objectMapper.convertValue(body, UpdateChannelRequest::class.java)
        val updateProjectId = body.has("projectId")
        return channelService.updateChannel(userId, channelId, request, updateProjectId)
    }

    @Operation(summary = "채널 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ) {
        channelService.deleteChannel(userId, channelId)
    }

    @Operation(summary = "채널 멤버 추가", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "추가 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PostMapping("/{channelId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: AddMemberRequest,
    ): ChannelMemberResponse = channelService.addMember(userId, channelId, request)

    @Operation(summary = "채널 멤버 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @GetMapping("/{channelId}/members")
    fun getMembers(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): List<ChannelMemberResponse> = channelService.getMembers(userId, channelId)

    @Operation(summary = "채널 멤버 제거", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @DeleteMapping("/{channelId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable memberId: Long,
    ) {
        channelService.removeMember(userId, channelId, memberId)
    }
}
