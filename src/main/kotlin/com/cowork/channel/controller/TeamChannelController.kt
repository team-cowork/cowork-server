package com.cowork.channel.controller

import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.service.ChannelService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class TeamChannelController(
    private val channelService: ChannelService,
) {

    @GetMapping("/teams/{teamId}/channels")
    fun getTeamChannels(@CurrentUser user: AuthUser, @PathVariable teamId: Long): List<ChannelResponse> =
        channelService.getTeamChannels(teamId, user.id)

    @GetMapping("/projects/{projectId}/channels")
    fun getProjectChannels(@CurrentUser user: AuthUser, @PathVariable projectId: Long): List<ChannelResponse> =
        channelService.getProjectChannels(projectId, user.id)
}
