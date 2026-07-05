package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.RemoveChannelMemberService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class RemoveChannelMemberServiceImpl(
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
    private val channelAccessGuard: ChannelAccessGuard,
) : RemoveChannelMemberService {

    override fun removeMember(userId: Long, channelId: Long, memberId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
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
