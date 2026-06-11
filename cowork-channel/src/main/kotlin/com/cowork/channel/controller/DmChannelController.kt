package com.cowork.channel.controller

import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.dto.OpenDmRequest
import com.cowork.channel.service.DmChannelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "DM", description = "1:1 DM 채널 열기 API")
@RestController
@RequestMapping("/dms")
class DmChannelController(
    private val dmChannelService: DmChannelService,
) {

    @Operation(summary = "DM 채널 열기 (멱등 — 이미 있으면 기존 채널 반환)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 또는 기존 채널 반환"),
        ApiResponse(responseCode = "400", description = "자기 자신과의 DM 생성 시도"),
    )
    @PostMapping
    fun openDm(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: OpenDmRequest,
    ): ResponseEntity<ChannelResponse> =
        ResponseEntity.status(201).body(dmChannelService.openDm(userId, request.targetUserId))
}
