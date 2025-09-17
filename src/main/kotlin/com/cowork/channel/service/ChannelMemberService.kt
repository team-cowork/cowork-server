package com.cowork.channel.service

import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.dto.ChannelMemberResponse
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.global.error.BusinessException
import com.cowork.team.service.TeamAccessChecker
import com.cowork.user.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelMemberService(
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    private val teamAccessChecker: TeamAccessChecker,
    private val userService: UserService,
) {

    @Transactional
    fun addMember(channelId: Long, actorId: Long, targetUserId: Long): ChannelMemberResponse {
        val channel = channelAccessChecker.requireManageable(channelId, actorId)
        if (!teamAccessChecker.isMember(channel.teamId, targetUserId)) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "팀 멤버만 채널에 추가할 수 있습니다.")
        }
        if (channelMemberRepository.existsByChannelIdAndUserId(channelId, targetUserId)) {
            throw BusinessException(HttpStatus.CONFLICT, "이미 채널에 속한 멤버입니다.")
        }
        val member = channelMemberRepository.save(ChannelMember(channelId = channelId, userId = targetUserId))
        return ChannelMemberResponse.of(member, userService.getSummaries(listOf(targetUserId))[targetUserId])
    }

    @Transactional
    fun getMembers(channelId: Long, actorId: Long): List<ChannelMemberResponse> {
        channelAccessChecker.requireReadable(channelId, actorId)
        val members = channelMemberRepository.findAllByChannelIdOrderByJoinedAtAsc(channelId)
        val users = userService.getSummaries(members.map { it.userId })
        return members.map { ChannelMemberResponse.of(it, users[it.userId]) }
    }
}
