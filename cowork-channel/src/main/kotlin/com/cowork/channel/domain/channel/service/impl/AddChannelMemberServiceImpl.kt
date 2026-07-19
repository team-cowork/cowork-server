package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.AddMemberRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelMemberResponse
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.service.AddChannelMemberService
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class AddChannelMemberServiceImpl(
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : AddChannelMemberService {

    @Transactional
    override fun execute(userId: Long, channelId: Long, request: AddMemberRequest): ChannelMemberResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)

        if (channel.isPrivate) {
            channelPermissionSupport.requireChannelManager(channel, userId)
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
}
