package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstone
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
class ChannelMessageReadPolicyEvaluator(
    private val membershipRepository: TeamMembershipRepository,
    private val assignmentRepository: TeamRoleAssignmentProjectionRepository,
    private val memberTombstoneRepository: TeamRoleMemberTombstoneRepository,
    private val roleRepository: TeamRoleProjectionRepository,
    private val policyRepository: ChannelRolePolicyProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
    private val objectMapper: ObjectMapper,
) {
    fun filterReadable(teamId: Long, userId: Long, channels: List<Channel>): List<Channel> {
        projectionReadinessGate.requireReady()
        return filterAccess(teamId, userId, channels)
    }

    internal fun filterAccess(teamId: Long, userId: Long, channels: List<Channel>): List<Channel> {
        if (channels.isEmpty()) return emptyList()
        val readableIds = readableChannelIds(teamId, userId, channels)
        return channels.filter { it.id in readableIds }
    }

    fun requireChannelAccess(teamId: Long, userId: Long, channel: Channel) {
        if (filterReadable(teamId, userId, listOf(channel)).isEmpty()) {
            val message = if (channel.type == ChannelType.VOICE) {
                "음성 채널 연결 권한이 없습니다."
            } else {
                "채널 메시지 읽기 권한이 없습니다."
            }
            throw ExpectedException(message, HttpStatus.FORBIDDEN)
        }
    }

    fun requirePermission(teamId: Long, userId: Long, channel: Channel, permission: String) {
        projectionReadinessGate.requireReady()
        if (channel.id !in readableChannelIds(teamId, userId, listOf(channel), permission)) {
            throw ExpectedException("해당 채널 작업 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    private fun readableChannelIds(
        teamId: Long,
        userId: Long,
        channels: List<Channel>,
        permission: String? = null,
    ): Set<Long> {
        if (channels.isEmpty()) return emptySet()
        val channelIds = channels.mapTo(mutableSetOf()) { it.id }
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
        val roles = loadRoles(teamId, assignedRoleIds)
        val policies = loadPolicies(teamId, channelIds, roles.keys)
        return channels.filter { isAllowed(it, assignedRoleIds, roles, policies[it.id].orEmpty(), permission) }
            .mapTo(mutableSetOf()) { it.id }
    }

    fun readableUserIds(channel: Channel, memberships: List<TeamMembership>): Set<Long> {
        projectionReadinessGate.requireReady()
        val teamId = channel.teamId ?: return emptySet()
        val activeMembers = memberships.filter { it.teamId == teamId && it.active }
        val allowed = activeMembers.filter { it.role == OWNER_ROLE }.mapTo(mutableSetOf()) { it.userId }
        val candidates = activeMembers.filter { it.role != OWNER_ROLE }.mapTo(mutableSetOf()) { it.userId }
        if (candidates.isEmpty()) return allowed
        val tombstones = memberTombstoneRepository.findAllById(
            candidates.map {
                TeamRoleMemberTombstone.key(teamId, it)
            },
        )
            .associateBy { it.accountId }
        val assignments = assignmentRepository.findAllByTeamIdAndAccountIdInAndDeletedFalse(teamId, candidates)
            .filter { assignment ->
                val fence = tombstones[assignment.accountId]
                fence == null || assignment.sourceOccurredAt.isAfter(fence.sourceOccurredAt)
            }.groupBy { it.accountId }
        val roles = loadRoles(teamId, assignments.values.flatten().mapTo(mutableSetOf()) { it.roleId })
        val policies = loadPolicies(teamId, setOf(channel.id), roles.keys)[channel.id].orEmpty()
        candidates.filterTo(allowed) { userId ->
            val roleIds = assignments[userId].orEmpty().mapTo(mutableSetOf()) { it.roleId }
            isAllowed(channel, roleIds, roles, policies)
        }
        return allowed
    }

    private data class RolePermissions(val priority: Int, val permissions: Map<String, Boolean>)

    private fun loadRoles(teamId: Long, roleIds: Set<Long>): Map<Long, RolePermissions> = if (roleIds.isEmpty()) {
        emptyMap()
    } else {
        roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, roleIds)
            .associate { it.roleId to RolePermissions(it.priority, permissions(it.permissionsJson)) }
    }

    private fun loadPolicies(
        teamId: Long,
        channelIds: Set<Long>,
        roleIds: Set<Long>,
    ): Map<Long, Map<Long, Map<String, Boolean>>> = if (roleIds.isEmpty()) {
        emptyMap()
    } else {
        policyRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(teamId, channelIds, roleIds)
            .groupBy { it.channelId }.mapValues { (_, policies) ->
                policies.associate { policy ->
                    val legacy = policy.messageRead?.let { mapOf("message_read" to it) }.orEmpty()
                    policy.roleId to (legacy + permissions(policy.permissionsJson))
                }
            }
    }

    private fun isAllowed(
        channel: Channel,
        roleIds: Set<Long>,
        roles: Map<Long, RolePermissions>,
        policies: Map<Long, Map<String, Boolean>>,
        permission: String? = null,
    ): Boolean {
        val assigned = roleIds.mapNotNull { roleId -> roles[roleId]?.let { roleId to it } }
        val highest = assigned.maxOfOrNull { it.second.priority } ?: return false
        val key = permission ?: if (channel.type == ChannelType.VOICE) "voice_connect" else "message_read"
        return assigned.filter { it.second.priority == highest }.all { (roleId, role) ->
            val default = if (channel.isPrivate && key in ACCESS_PERMISSIONS) false else role.permissions[key] ?: false
            policies[roleId]?.get(key) ?: default
        }
    }

    private fun permissions(json: String?): Map<String, Boolean> = json?.let {
        objectMapper.readValue(it, object : TypeReference<Map<String, Boolean>>() {})
    }.orEmpty()

    private companion object {
        const val OWNER_ROLE = "OWNER"
        val ACCESS_PERMISSIONS = setOf("message_read", "voice_connect")
    }
}
