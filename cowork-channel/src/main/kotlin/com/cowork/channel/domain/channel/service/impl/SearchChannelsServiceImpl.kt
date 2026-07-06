package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.SearchChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SearchChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
) : SearchChannelsService {

    override fun execute(userId: Long, teamId: Long, q: String): List<ChannelResponse> {
        teamPermissionService.requireTeamMember(teamId, userId)
        if (q.isBlank()) return emptyList()
        return channelRepository.searchByTeamIdAndName(teamId, q).map { ChannelResponse.of(it) }
    }
}
