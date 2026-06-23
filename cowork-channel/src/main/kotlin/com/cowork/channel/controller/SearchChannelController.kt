package com.cowork.channel.controller

import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.service.ChannelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "검색", description = "전체 검색 API")
@RestController
@RequestMapping("/search")
class SearchChannelController(private val channelService: ChannelService) {

    @Operation(summary = "채널 검색", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검색 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @GetMapping("/channels")
    fun searchChannels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestParam teamId: Long,
        @RequestParam q: String,
    ): List<ChannelResponse> = channelService.searchChannels(userId, teamId, q)
}
