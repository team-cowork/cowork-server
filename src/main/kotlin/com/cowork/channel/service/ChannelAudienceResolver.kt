package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.team.repository.TeamMemberRepository
import org.springframework.stereotype.Component

@Component
class ChannelAudienceResolver(
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamMemberRepository: TeamMemberRepository,
) {

    fun resolve(channel: Channel): Set<Long> =
        if (channel.isPrivate) {
            channelMemberRepository.findUserIdsByChannelId(channel.id).toSet()
        } else {
            teamMemberRepository.findUserIdsByTeamId(channel.teamId).toSet()
        }
}
