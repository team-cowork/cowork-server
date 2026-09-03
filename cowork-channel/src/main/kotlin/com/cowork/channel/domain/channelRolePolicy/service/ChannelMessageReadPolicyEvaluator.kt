package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstone
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class ChannelMessageReadPolicyEvaluator(
    private val membershipRepository: TeamMembershipRepository,
    private val assignmentRepository: TeamRoleAssignmentProjectionRepository,
    private val memberTombstoneRepository: TeamRoleMemberTombstoneRepository,
    private val roleRepository: TeamRoleProjectionRepository,
    private val policyRepository: ChannelRolePolicyProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
) {
    fun filterReadable(teamId: Long, userId: Long, channels: List<Channel>): List<Channel> {
        if (channels.isEmpty()) return emptyList()
        val readableIds = readableChannelIds(teamId, userId, channels.mapTo(mutableSetOf()) { it.id })
        return channels.filter { it.id in readableIds }
    }

    fun requireMessageRead(teamId: Long, userId: Long, channelId: Long) {
        if (channelId !in readableChannelIds(teamId, userId, setOf(channelId))) {
            throw ExpectedException("채널 메시지 읽기 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    internal fun readableChannelIds(teamId: Long, userId: Long, channelIds: Set<Long>): Set<Long> {
        if (channelIds.isEmpty()) return emptySet()
        projectionReadinessGate.requireReady()
        val membership = membershipRepository.findByTeamIdAndUserId(teamId, userId) ?: return emptySet()
        if (membership.role == OWNER_ROLE) return channelIds

        val memberTombstone = memberTombstoneRepository.findById(
            TeamRoleMemberTombstone.key(teamId, userId),
        ).orElse(null)
        val assignedRoleIds = assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(teamId, userId)
            .asSequence()
            .filter { assignment ->
                memberTombstone == null || assignment.sourceOccurredAt.isAfter(memberTombstone.sourceOccurredAt)
            }
            .mapTo(mutableSetOf()) { it.roleId }
        if (assignedRoleIds.isEmpty()) return emptySet()
        val rolesById = roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, assignedRoleIds)
            .associateBy { it.roleId }
        if (rolesById.isEmpty()) return emptySet()
        val activeRoleIds = rolesById.keys
        val policiesByChannel = policyRepository
            .findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(teamId, channelIds, activeRoleIds)
            .groupBy { it.channelId }

        return channelIds.filterTo(mutableSetOf()) { channelId ->
            val applicable = policiesByChannel[channelId].orEmpty()
                .filter { it.messageRead != null && it.roleId in rolesById }
            val highestPriority = applicable.maxOfOrNull { rolesById.getValue(it.roleId).priority }
                ?: return@filterTo false
            applicable
                .filter { rolesById.getValue(it.roleId).priority == highestPriority }
                .all { it.messageRead == true }
        }
    }

    private companion object {
        const val OWNER_ROLE = "OWNER"
    }
}
