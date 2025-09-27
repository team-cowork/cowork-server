package com.cowork.channel.controller

import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.dto.ReorderChannelsRequest
import com.cowork.channel.service.ChannelService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "채널")
@RestController
class TeamChannelController(
    private val channelService: ChannelService,
) {

    @Operation(summary = "팀 채널 목록 조회")
    @GetMapping("/teams/{teamId}/channels")
    fun getTeamChannels(@CurrentUser user: AuthUser, @PathVariable teamId: Long): List<ChannelResponse> =
        channelService.getTeamChannels(teamId, user.id)

    @Operation(summary = "팀 채널 순서 변경")
    @PatchMapping("/teams/{teamId}/channels/reorder")
    fun reorder(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @Valid @RequestBody request: ReorderChannelsRequest,
    ): List<ChannelResponse> = channelService.reorder(teamId, user.id, request.channelIds)

    @Operation(summary = "프로젝트 채널 목록 조회")
    @GetMapping("/projects/{projectId}/channels")
    fun getProjectChannels(@CurrentUser user: AuthUser, @PathVariable projectId: Long): List<ChannelResponse> =
        channelService.getProjectChannels(projectId, user.id)
}
