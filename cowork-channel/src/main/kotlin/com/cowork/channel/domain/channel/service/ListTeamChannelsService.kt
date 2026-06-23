package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.repository.ChannelRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListTeamChannelsService(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
) {

    @Transactional(readOnly = true)
    fun execute(userId: Long, teamId: Long): List<Channel> {
        teamPermissionService.requireTeamMember(teamId, userId)
        return channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId)
    }
}
