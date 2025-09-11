package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.dto.CreateChannelRequest
import com.cowork.channel.dto.UpdateChannelRequest
import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.global.error.BusinessException
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelService(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    private val teamAccessChecker: TeamAccessChecker,
    private val projectAccessChecker: ProjectAccessChecker,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun create(userId: Long, request: CreateChannelRequest): ChannelResponse {
        teamAccessChecker.requirePermission(request.teamId, userId, TeamPermission.MANAGE_CHANNELS)
        request.projectId?.let { validateProject(request.teamId, it) }

        val channel = channelRepository.save(
            Channel(
                teamId = request.teamId,
                projectId = request.projectId,
                name = request.name,
                viewType = request.viewType,
                description = request.description,
                isPrivate = request.isPrivate,
                position = channelRepository.findMaxPosition(request.teamId) + 1,
                createdBy = userId,
            ),
        )
        if (channel.isPrivate) {
            channelMemberRepository.save(ChannelMember(channelId = channel.id, userId = userId))
        }
        return ChannelResponse.from(channel)
    }

    @Transactional
    fun getChannel(channelId: Long, userId: Long): ChannelResponse =
        ChannelResponse.from(channelAccessChecker.requireReadable(channelId, userId))

    @Transactional
    fun update(channelId: Long, userId: Long, request: UpdateChannelRequest): ChannelResponse {
        val channel = channelAccessChecker.requireManageable(channelId, userId)
        channel.update(request.name, request.description, request.isPrivate)
        when {
            request.detachProject -> channel.assignProject(null)
            request.projectId != null -> {
                validateProject(channel.teamId, request.projectId)
                channel.assignProject(request.projectId)
            }
        }
        return ChannelResponse.from(channel)
    }

    @Transactional
    fun delete(channelId: Long, userId: Long) {
        val channel = channelAccessChecker.requireManageable(channelId, userId)
        deleteChannel(channel)
    }

    fun deleteChannel(channel: Channel) {
        channelMemberRepository.deleteAllByChannelId(channel.id)
        channelRepository.delete(channel)
        eventPublisher.publishEvent(ChannelDeletedEvent(channel.id, channel.teamId))
    }

    private fun validateProject(teamId: Long, projectId: Long) {
        val project = projectAccessChecker.findProject(projectId)
        if (project.teamId != teamId) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "같은 팀의 프로젝트만 연결할 수 있습니다.")
        }
    }
}
