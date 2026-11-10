package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.UpdateChannelService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.project.repository.ProjectProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class UpdateChannelServiceImpl(
    private val channelEventPublisher: ChannelEventPublisher,
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
    private val projectProjectionRepository: ProjectProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
) : UpdateChannelService {

    @Transactional
    override fun execute(
        userId: Long,
        channelId: Long,
        request: UpdateChannelRequest,
        updateProjectId: Boolean,
    ): ChannelResponse {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        channelPermissionSupport.requireChannelManager(channel, userId)
        // 보관된 채널도 열람 대상은 조정할 수 있도록 공개 여부 변경만 허용한다.
        if (request.name != null || request.description != null || updateProjectId) {
            channelAccessGuard.requireNotArchived(channel)
        }
        if (!channel.isPrivate && request.isPrivate == true) {
            channelPermissionSupport.requirePrivateTransitionAccess(channel, userId)
        }
        val projectId = request.projectId
        if (updateProjectId && projectId != null && projectId != channel.projectId) {
            requireSameTeamProject(projectId, teamId)
        }
        channel.update(request.name, request.description, request.isPrivate)
        if (updateProjectId) channel.assignProject(request.projectId)
        channelEventPublisher.publishUpdated(channel)
        return ChannelResponse.of(channel)
    }

    private fun requireSameTeamProject(projectId: Long, teamId: Long) {
        projectionReadinessGate.requireReady()
        val projection = projectProjectionRepository.findById(projectId).orElseThrow {
            ExpectedException("프로젝트 동기화 정보가 준비되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        if (projection.deleted || projection.teamId != teamId) {
            throw ExpectedException("프로젝트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }
}
