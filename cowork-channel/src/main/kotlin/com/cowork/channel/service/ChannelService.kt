package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.*
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
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
) {

    fun findChannelOrThrow(channelId: Long): Channel =
        channelRepository.findById(channelId).orElseThrow {
            ExpectedException("채널을 찾을 수 없습니다. id=$channelId", HttpStatus.NOT_FOUND)
        }

    private fun parseType(value: String): ChannelType = try {
        ChannelType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw ExpectedException("유효하지 않은 채널 타입입니다. type=$value", HttpStatus.BAD_REQUEST)
    }

    private fun parseViewType(value: String): ChannelViewType = try {
        ChannelViewType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw ExpectedException("유효하지 않은 view_type 입니다. value=$value", HttpStatus.BAD_REQUEST)
    }

    private fun requireChannelManager(channel: Channel, userId: Long) {
        if (channel.createdBy == userId) return
        if (teamPermissionService.isTeamOwnerOrAdmin(channel.teamId, userId)) return
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
                createdBy = userId,
            )
        )
        channelMemberRepository.save(ChannelMember(channelId = channel.id, userId = userId))
        return ChannelResponse.of(channel)
    }

    fun getChannel(userId: Long, channelId: Long): ChannelResponse {
        val channel = findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channel.teamId, userId)
        return ChannelResponse.of(channel)
    }

    @Transactional
    fun updateChannel(userId: Long, channelId: Long, request: UpdateChannelRequest): ChannelResponse {
        val channel = findChannelOrThrow(channelId)
        requireChannelManager(channel, userId)
        channel.update(request.name, request.description, request.isPrivate)
        return ChannelResponse.of(channel)
    }

    @Transactional
    fun deleteChannel(userId: Long, channelId: Long) {
        val channel = findChannelOrThrow(channelId)
        requireChannelManager(channel, userId)
        channelRepository.delete(channel)
    }

    @Transactional
    fun addMember(userId: Long, channelId: Long, request: AddMemberRequest): ChannelMemberResponse {
        val channel = findChannelOrThrow(channelId)

        if (channel.isPrivate) {
            requireChannelManager(channel, userId)
        } else {
            teamPermissionService.requireTeamMember(channel.teamId, userId)
        }

        if (!teamPermissionService.isTeamMember(channel.teamId, request.userId)) {
            throw ExpectedException("추가 대상이 팀 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        if (channelMemberRepository.existsByChannelIdAndUserId(channelId, request.userId)) {
            throw ExpectedException("이미 채널에 참여한 멤버입니다.", HttpStatus.CONFLICT)
        }

        val member = channelMemberRepository.save(
            ChannelMember(channelId = channelId, userId = request.userId)
        )
        return ChannelMemberResponse.of(member)
    }

    fun getMembers(userId: Long, channelId: Long): List<ChannelMemberResponse> {
        val channel = findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channel.teamId, userId)
        return channelMemberRepository.findByChannelId(channelId).map { ChannelMemberResponse.of(it) }
    }

    @Transactional
    fun removeMember(userId: Long, channelId: Long, memberId: Long) {
        val channel = findChannelOrThrow(channelId)
        val member = channelMemberRepository.findById(memberId).orElseThrow {
            ExpectedException("멤버를 찾을 수 없습니다. id=$memberId", HttpStatus.NOT_FOUND)
        }

        if (member.channelId != channelId) {
            throw ExpectedException("해당 채널의 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        if (member.userId == channel.createdBy) {
            throw ExpectedException("채널 생성자는 채널을 떠날 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        val isCreator = channel.createdBy == userId
        val isSelf = member.userId == userId
        val isTeamManager = teamPermissionService.isTeamOwnerOrAdmin(channel.teamId, userId)

        if (!isCreator && !isSelf && !isTeamManager) {
            throw ExpectedException("멤버 제거 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }

        channelMemberRepository.delete(member)
    }
}
