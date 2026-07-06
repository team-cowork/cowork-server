package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ListProjectChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.global.client.ProjectClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ListProjectChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val projectClient: ProjectClient,
) : ListProjectChannelsService {

    override fun execute(userId: Long, projectId: Long): List<ChannelResponse> {
        val teamId = projectClient.getTeamId(projectId)
        teamPermissionService.requireTeamMember(teamId, userId)
        return channelRepository.findAllByProjectIdOrderByIdAsc(projectId).map { ChannelResponse.of(it) }
    }
}
