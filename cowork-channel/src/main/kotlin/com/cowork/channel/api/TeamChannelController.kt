package com.cowork.channel.api

import com.cowork.channel.channel.ListTeamChannelsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "채널", description = "팀 채널 조회 API")
@RestController
@RequestMapping("/teams")
class TeamChannelController(
    private val listTeamChannelsService: ListTeamChannelsService,
) {

    @Operation(summary = "팀 채널 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "해당 팀의 멤버가 아님"),
    )
    @GetMapping("/{teamId}/channels")
    fun listTeamChannels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): List<ChannelResponse> =
        listTeamChannelsService.execute(userId, teamId).map(ChannelResponse::from)
}
