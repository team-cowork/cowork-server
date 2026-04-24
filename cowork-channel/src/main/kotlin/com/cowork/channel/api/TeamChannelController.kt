package com.cowork.channel.api

import com.cowork.channel.channel.ChannelService
import com.cowork.channel.client.TeamClient
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.exception.ExpectedException

@RestController
@RequestMapping("/teams")
class TeamChannelController(
    private val channelService: ChannelService,
    private val teamClient: TeamClient,
) {

    @GetMapping("/{teamId}/channels")
    fun listTeamChannels(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): List<ChannelResponse> {
        val isMember = teamClient.getMembers(teamId).any { it.userId == userId }
        if (!isMember) throw ExpectedException("해당 팀의 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        return channelService.listByTeam(teamId).map(ChannelResponse::from)
    }
}
