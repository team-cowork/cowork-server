package com.cowork.channel.api

import com.cowork.channel.channel.ChannelService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/teams")
class TeamChannelController(
    private val channelService: ChannelService,
) {

    @GetMapping("/{teamId}/channels")
    fun listTeamChannels(
        @PathVariable teamId: Long,
    ): List<ChannelResponse> =
        channelService.listByTeam(teamId)
            .map(ChannelResponse::from)
}

