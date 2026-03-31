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

/**
 * 채널 생성, 조회, 수정, 삭제와 순서 변경을 담당한다.
 *
 * - 비공개 채널은 채널 멤버와 채널 관리 권한이 있는 사용자만 볼 수 있다.
 * - 채널은 같은 팀의 프로젝트에만 연결할 수 있다.
 */
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

    @Transactional(readOnly = true)
    fun getChannel(channelId: Long, userId: Long): ChannelResponse =
        ChannelResponse.from(channelAccessChecker.requireReadable(channelId, userId))

    @Transactional(readOnly = true)
    fun getTeamChannels(teamId: Long, userId: Long): List<ChannelResponse> {
        teamAccessChecker.requireMember(teamId, userId)
        return filterReadable(channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId), userId)
    }

    @Transactional(readOnly = true)
    fun getProjectChannels(projectId: Long, userId: Long): List<ChannelResponse> {
        projectAccessChecker.requireReadable(projectId, userId)
        return filterReadable(channelRepository.findAllByProjectIdOrderByPositionAscIdAsc(projectId), userId)
    }

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

    @Transactional
    fun reorder(teamId: Long, userId: Long, channelIds: List<Long>): List<ChannelResponse> {
        teamAccessChecker.requirePermission(teamId, userId, TeamPermission.MANAGE_CHANNELS)
        val channels = channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId)
        if (channelIds.size != channels.size || channelIds.toSet() != channels.map { it.id }.toSet()) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "팀의 모든 채널을 중복 없이 전달해야 합니다.")
        }
        val channelById = channels.associateBy { it.id }
        channelIds.forEachIndexed { index, id -> channelById.getValue(id).moveTo(index) }
        return channelIds.map { ChannelResponse.from(channelById.getValue(it)) }
    }

    fun deleteChannel(channel: Channel) {
        eventPublisher.publishEvent(ChannelDeletedEvent(channel.id, channel.teamId))
        channelMemberRepository.deleteAllByChannelId(channel.id)
        channelRepository.delete(channel)
    }

    private fun validateProject(teamId: Long, projectId: Long) {
        val project = projectAccessChecker.findProject(projectId)
        if (project.teamId != teamId) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "같은 팀의 프로젝트만 연결할 수 있습니다.")
        }
    }

    private fun filterReadable(channels: List<Channel>, userId: Long): List<ChannelResponse> {
        val privateIds = channels.filter { it.isPrivate }.map { it.id }
        val joined = if (privateIds.isEmpty()) emptySet() else channelMemberRepository.findJoinedChannelIds(userId, privateIds).toSet()
        val canManageAll = channels.firstOrNull()?.let { channelAccessChecker.canManage(it, userId) } ?: false
        return channels
            .filter { !it.isPrivate || canManageAll || it.id in joined }
            .map(ChannelResponse::from)
    }
}
