package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyResponse
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.service.QueryChannelRolePoliciesService
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryChannelRolePoliciesServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val channelPermissionSupport: ChannelPermissionSupport,
    private val policyRepository: ChannelRolePolicyProjectionRepository,
    private val roleRepository: TeamRoleProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
) : QueryChannelRolePoliciesService {
    @Transactional(readOnly = true)
    override fun execute(actorId: Long, channelId: Long): List<ChannelRolePolicyResponse> {
        projectionReadinessGate.requireReady()
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        teamPermissionService.requireTeamMember(teamId, actorId)
        channelPermissionSupport.requireChannelManager(channel, actorId)
        val policies = policyRepository.findAllByTeamIdAndChannelIdAndDeletedFalse(teamId, channelId)
        if (policies.isEmpty()) return emptyList()
        val rolesById = roleRepository
            .findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, policies.mapTo(mutableSetOf()) { it.roleId })
            .associateBy { it.roleId }
        return policies.asSequence()
            .filter { it.roleId in rolesById && it.messageRead != null }
            .sortedWith(
                compareByDescending<ChannelRolePolicyProjection> {
                    rolesById.getValue(it.roleId).priority
                }.thenBy { it.roleId },
            )
            .map {
                ChannelRolePolicyResponse(
                    roleId = it.roleId,
                    priority = rolesById.getValue(it.roleId).priority,
                    permissions = mapOf(MESSAGE_READ_KEY to requireNotNull(it.messageRead)),
                )
            }
            .toList()
    }

    private companion object {
        const val MESSAGE_READ_KEY = "message_read"
    }
}
