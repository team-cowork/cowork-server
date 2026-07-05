package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ListTeamChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ListTeamChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
) : ListTeamChannelsService {

    override fun listTeamChannels(userId: Long, teamId: Long): List<Channel> {
        teamPermissionService.requireTeamMember(teamId, userId)
        return channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId)
    }
}
