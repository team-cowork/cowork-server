package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMembershipSyncPublisher
import com.cowork.channel.domain.channel.presentation.data.request.AddMemberRequest
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelMemberResponse
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.client.ProjectClient
import com.cowork.channel.domain.meetingNote.service.MeetingNoteTemplateService
import com.cowork.channel.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class ChannelService(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
    private val channelMembershipSyncPublisher: ChannelMembershipSyncPublisher,
    private val channelEventPublisher: ChannelEventPublisher,
    private val projectClient: ProjectClient,
    private val meetingNoteTemplateService: MeetingNoteTemplateService,
) {

    fun findChannelOrThrow(channelId: Long): Channel = channelRepository.findById(channelId).orElseThrow {
        ExpectedException("채널을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    /** DM 채널이면 거부하고, 팀 채널이면 non-null teamId를 반환한다. */
    fun requireTeamChannel(channel: Channel): Long {
        val teamId = channel.teamId
        if (channel.type == ChannelType.DM || teamId == null) {
            throw ExpectedException("DM 채널에서는 지원하지 않는 기능입니다.", HttpStatus.BAD_REQUEST)
        }
        return teamId
    }

    /** 팀 채널은 팀 멤버십, DM 채널은 채널 멤버십으로 접근 권한을 검사한다. */
    private fun requireChannelAccess(channel: Channel, userId: Long) {
        val teamId = channel.teamId
        if (teamId == null) {
            if (!channelMemberRepository.existsByChannelIdAndUserId(channel.id, userId)) {
                throw ExpectedException("채널 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
            }
        } else {
            teamPermissionService.requireTeamMember(teamId, userId)
        }
    }

    private fun parseType(value: String): ChannelType {
        val type = try {
            ChannelType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ExpectedException("유효하지 않은 채널 타입입니다.", HttpStatus.BAD_REQUEST)
        }
        if (type == ChannelType.DM) {
            throw ExpectedException("DM 채널은 DM 전용 API로만 생성할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        return type
    }

    private fun parseViewType(value: String): ChannelViewType = try {
        ChannelViewType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw ExpectedException("유효하지 않은 view_type 입니다.", HttpStatus.BAD_REQUEST)
    }

    private fun requireChannelManager(channel: Channel, userId: Long) {
        if (channel.createdBy == userId) return
        val teamId = channel.teamId ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        if (teamPermissionService.isTeamOwnerOrAdmin(teamId, userId)) return
        throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    @Transactional
    fun createChannel(userId: Long, request: CreateChannelRequest): ChannelResponse {
        teamPermissionService.requireTeamMember(request.teamId, userId)

        val channel = channelRepository.save(
            Channel(
                teamId = request.teamId,
                name = request.name,
                type = parseType(request.type),
                viewType = parseViewType(request.viewType),
                description = request.description,
                isPrivate = request.isPrivate,
                position = channelRepository.findMaxPositionByTeamId(request.teamId) + 1,
                createdBy = userId,
            ),
        )
        val member = channelMemberRepository.save(ChannelMember(channelId = channel.id, userId = userId))
        if (channel.viewType == ChannelViewType.MEETING_NOTE) {
            meetingNoteTemplateService.createDefaultTemplate(channel)
        }
        afterCommit {
            channelMembershipSyncPublisher.publishChannelSnapshot(channel, listOf(member))
            channelEventPublisher.publishCreated(channel)
        }
        return ChannelResponse.of(channel)
    }

    fun getChannel(userId: Long, channelId: Long): ChannelResponse {
        val channel = findChannelOrThrow(channelId)
        requireChannelAccess(channel, userId)
        return ChannelResponse.of(channel)
    }

    @Transactional
    fun reorderTeamChannels(userId: Long, teamId: Long, orderedChannelIds: List<Long>): List<ChannelResponse> {
        teamPermissionService.requireTeamMember(teamId, userId)

        if (orderedChannelIds.isEmpty()) {
            throw ExpectedException("채널 순서 목록은 비어 있을 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val inputIds = orderedChannelIds.toSet()
        if (inputIds.size != orderedChannelIds.size) {
            throw ExpectedException("채널 순서 목록에 중복 ID가 포함되어 있습니다.", HttpStatus.BAD_REQUEST)
        }

        val channels = channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId)
        val teamChannelIds = channels.map { it.id }.toSet()
        if (inputIds != teamChannelIds) {
            throw ExpectedException("팀의 모든 채널 ID를 정확히 포함해야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val channelById = channels.associateBy { it.id }
        orderedChannelIds.forEachIndexed { index, channelId ->
            channelById[channelId]?.updatePosition(index)
        }

        return orderedChannelIds.mapNotNull { channelById[it] }.map(ChannelResponse::of)
    }

    @Transactional
    fun updateChannel(
        userId: Long,
        channelId: Long,
        request: UpdateChannelRequest,
        updateProjectId: Boolean = false,
    ): ChannelResponse {
        val channel = findChannelOrThrow(channelId)
        requireTeamChannel(channel)
        requireChannelManager(channel, userId)
        channel.update(request.name, request.description, request.isPrivate)
        if (updateProjectId) channel.assignProject(request.projectId)
        afterCommit { channelEventPublisher.publishUpdated(channel) }
        return ChannelResponse.of(channel)
    }

    fun searchChannels(userId: Long, teamId: Long, q: String): List<ChannelResponse> {
        teamPermissionService.requireTeamMember(teamId, userId)
        if (q.isBlank()) return emptyList()
        return channelRepository.searchByTeamIdAndName(teamId, q).map { ChannelResponse.of(it) }
    }

    fun listProjectChannels(userId: Long, projectId: Long): List<ChannelResponse> {
        val teamId = projectClient.getTeamId(projectId)
        teamPermissionService.requireTeamMember(teamId, userId)
        return channelRepository.findAllByProjectIdOrderByIdAsc(projectId).map { ChannelResponse.of(it) }
    }

    @Transactional
    fun deleteChannel(userId: Long, channelId: Long) {
        val channel = findChannelOrThrow(channelId)
        requireTeamChannel(channel)
        requireChannelManager(channel, userId)
        val members = channelMemberRepository.findByChannelId(channelId)
        channelRepository.delete(channel)
        afterCommit {
            members.forEach { member ->
                channelMemberEventPublisher.publishLeave(channel.id, channel.teamId, member.userId, channel.type.name)
            }
            channelEventPublisher.publishDeleted(channel)
        }
    }

    @Transactional
    fun addMember(userId: Long, channelId: Long, request: AddMemberRequest): ChannelMemberResponse {
        val channel = findChannelOrThrow(channelId)
        val teamId = requireTeamChannel(channel)

        if (channel.isPrivate) {
            requireChannelManager(channel, userId)
        } else {
            teamPermissionService.requireTeamMember(teamId, userId)
        }

        if (!teamPermissionService.isTeamMember(teamId, request.userId)) {
            throw ExpectedException("추가 대상이 팀 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        if (channelMemberRepository.existsByChannelIdAndUserId(channelId, request.userId)) {
            throw ExpectedException("이미 채널에 참여한 멤버입니다.", HttpStatus.CONFLICT)
        }

        val member = channelMemberRepository.save(
            ChannelMember(channelId = channelId, userId = request.userId),
        )
        afterCommit {
            channelMemberEventPublisher.publishJoin(channel.id, channel.teamId, request.userId, channel.type.name)
        }
        return ChannelMemberResponse.of(member)
    }

    fun getMembers(userId: Long, channelId: Long): List<ChannelMemberResponse> {
        val channel = findChannelOrThrow(channelId)
        requireChannelAccess(channel, userId)
        return channelMemberRepository.findByChannelId(channelId).map { ChannelMemberResponse.of(it) }
    }

    @Transactional
    fun removeMember(userId: Long, channelId: Long, memberId: Long) {
        val channel = findChannelOrThrow(channelId)
        val teamId = requireTeamChannel(channel)
        val member = channelMemberRepository.findById(memberId).orElseThrow {
            ExpectedException("멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }

        if (member.channelId != channelId) {
            throw ExpectedException("해당 채널의 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        if (member.userId == channel.createdBy) {
            throw ExpectedException("채널 생성자는 채널을 떠날 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        val isCreator = channel.createdBy == userId
        val isSelf = member.userId == userId
        val isTeamManager = teamPermissionService.isTeamOwnerOrAdmin(teamId, userId)

        if (!isCreator && !isSelf && !isTeamManager) {
            throw ExpectedException("멤버 제거 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }

        channelMemberRepository.delete(member)
        afterCommit {
            channelMemberEventPublisher.publishLeave(channel.id, channel.teamId, member.userId, channel.type.name)
        }
    }
}
