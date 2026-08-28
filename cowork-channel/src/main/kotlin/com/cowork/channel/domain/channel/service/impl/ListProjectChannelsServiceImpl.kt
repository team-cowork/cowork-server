package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ListProjectChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.project.repository.ProjectProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ListProjectChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val projectProjectionRepository: ProjectProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
) : ListProjectChannelsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): List<ChannelResponse> {
        projectionReadinessGate.requireReady()
        val projection = projectProjectionRepository.findById(projectId).orElseThrow {
            ExpectedException("프로젝트 동기화 정보가 준비되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        if (projection.deleted) {
            throw ExpectedException("프로젝트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val teamId = projection.teamId
        teamPermissionService.requireTeamMember(teamId, userId)
        return channelRepository.findAllByProjectIdOrderByIdAsc(projectId).map { ChannelResponse.of(it) }
    }
}
