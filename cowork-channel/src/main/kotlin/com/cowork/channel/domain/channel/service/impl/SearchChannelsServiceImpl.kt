package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.SearchChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SearchChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val messageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
) : SearchChannelsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long, q: String): List<ChannelResponse> {
        teamPermissionService.requireTeamMember(teamId, userId)
        if (q.isBlank()) return emptyList()
        val structurallyVisible = channelRepository.searchVisibleByTeamIdAndName(teamId, userId, q)
        return messageReadPolicyEvaluator.filterReadable(teamId, userId, structurallyVisible).map {
            ChannelResponse.of(it)
        }
    }
}
