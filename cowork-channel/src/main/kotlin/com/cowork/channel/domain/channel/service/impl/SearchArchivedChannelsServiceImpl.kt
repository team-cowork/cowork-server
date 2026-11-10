package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.SearchArchivedChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SearchArchivedChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val messageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
) : SearchArchivedChannelsService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        teamId: Long,
        channelId: Long?,
        projectId: Long?,
        q: String?,
    ): List<ChannelResponse> {
        teamPermissionService.requireTeamMember(teamId, userId)
        val archived = channelRepository.searchArchived(teamId, channelId, projectId, q?.trim()?.ifEmpty { null })
        return messageReadPolicyEvaluator.filterReadable(teamId, userId, archived).map(ChannelResponse::of)
    }
}
