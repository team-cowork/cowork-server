package com.cowork.channel.channel

import com.cowork.channel.client.TeamClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamChannelService(
    private val channelRepository: ChannelRepository,
    private val teamClient: TeamClient,
) {

    @Transactional(readOnly = true)
    fun listByTeam(userId: Long, teamId: Long): List<ChannelEntity> {
        val isMember = teamClient.isMember(teamId, userId)["isMember"] == true
        if (!isMember) throw ExpectedException("해당 팀의 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        return channelRepository.findAllByTeamIdOrderByIdAsc(teamId)
    }
}
