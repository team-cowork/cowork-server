package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelAccessUserResponse
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.QueryChannelMembersService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryChannelMembersServiceImpl(
    private val teamMembers: TeamMembershipRepository,
    private val evaluator: ChannelMessageReadPolicyEvaluator,
    private val dmParticipantRepository: DmParticipantRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : QueryChannelMembersService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, channelId: Long): List<ChannelAccessUserResponse> {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelPermissionSupport.requireChannelAccess(channel, userId)
        val teamId = channel.teamId
            ?: return dmParticipantRepository.findByChannelId(channelId).map(ChannelAccessUserResponse::of)
        return teamMembers.findAllByTeamIdAndActiveTrue(teamId)
            .filter { evaluator.filterReadable(teamId, it.userId, listOf(channel)).isNotEmpty() }
            .map { ChannelAccessUserResponse(channelId, it.userId) }
    }
}
