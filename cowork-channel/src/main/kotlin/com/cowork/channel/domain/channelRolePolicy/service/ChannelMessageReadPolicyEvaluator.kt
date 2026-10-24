package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.event.InitialChannelRoleOverwrite
import com.cowork.channel.domain.channel.event.InitialChannelRolePolicySeed
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstone
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyPermissionSchema
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Instant

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

    fun requireReorder(teamId: Long, userId: Long, channels: List<Channel>) {
        projectionReadinessGate.requireReady()
        val membership = membershipRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (membership.active && membership.role in BYPASS_ROLES) return
        val candidates = channels
        val allowed = readableChannelIds(teamId, userId, candidates, "channel_reorder")
        if (candidates.any { it.id !in allowed }) {
            throw ExpectedException("채널 재정렬 권한이 없습니다.", HttpStatus.FORBIDDEN)
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
        if (!membership.active) return emptySet()
        if (membership.role in BYPASS_ROLES) return channelIds

        val memberTombstone = memberTombstoneRepository.findById(
            TeamRoleMemberTombstone.key(teamId, userId),
        ).orElse(null)
        val assignedRoleIds = assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(teamId, userId)
            .asSequence()
            .filter { assignment ->
                memberTombstone == null || assignment.sourceOccurredAt.isAfter(memberTombstone.sourceOccurredAt)
            }
            .mapTo(mutableSetOf()) { it.roleId }
        val roles = loadRoles(teamId, assignedRoleIds)
        val policies = loadPolicies(teamId, channelIds, roles.keys)
        return channels.filter { isAllowed(it, assignedRoleIds, roles, policies[it.id].orEmpty(), permission) }
            .mapTo(mutableSetOf()) { it.id }
    }

    fun readableUserIds(channel: Channel, memberships: List<TeamMembership>): Set<Long> {
        projectionReadinessGate.requireReady()
        val teamId = channel.teamId ?: return emptySet()
        val activeMembers = memberships.filter { it.teamId == teamId && it.active }
        val allowed = activeMembers.filter { it.role in BYPASS_ROLES }.mapTo(mutableSetOf()) { it.userId }
        val candidates = activeMembers.filter { it.role !in BYPASS_ROLES }.mapTo(mutableSetOf()) { it.userId }
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

    private data class RolePermissions(val priority: Int, val isDefault: Boolean, val permissions: Map<String, Boolean>)

    private fun loadRoles(teamId: Long, roleIds: Set<Long>): Map<Long, RolePermissions> {
        val defaults = roleRepository.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(teamId)
        val assigned = if (roleIds.isEmpty()) {
            emptyList()
        } else {
            roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, roleIds)
        }
        return (defaults + assigned).associate {
            it.roleId to RolePermissions(it.priority, it.isDefault, permissions(it.permissionsJson))
        }
    }

    fun requireTeamPermission(teamId: Long, userId: Long, permission: String) {
        projectionReadinessGate.requireReady()
        val membership = membershipRepository.findByTeamIdAndUserId(teamId, userId)
        if (membership == null || !membership.active) {
            throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        if (membership.role in BYPASS_ROLES) return
        val fence = memberTombstoneRepository.findById(TeamRoleMemberTombstone.key(teamId, userId)).orElse(null)
        val roleIds = assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(teamId, userId)
            .filter { fence == null || it.sourceOccurredAt.isAfter(fence.sourceOccurredAt) }
            .mapTo(mutableSetOf()) { it.roleId }
        if (loadRoles(teamId, roleIds).values.none { it.permissions[permission] == true }) {
            throw ExpectedException("해당 팀 작업 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun prepareInitialRolePolicySeed(
        teamId: Long,
        userId: Long,
        channel: Channel,
        overwrites: List<InitialChannelRoleOverwrite>,
    ): InitialChannelRolePolicySeed? {
        requireTeamPermission(teamId, userId, "manage_channels")
        val membership = membershipRepository.findByTeamIdAndUserId(teamId, userId)
            ?.takeIf { it.active }
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (overwrites.isNotEmpty() && !membership.sourceOccurredAt.isAfter(Instant.EPOCH)) {
            throw ExpectedException("팀 멤버십 정보 동기화가 필요합니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        val targetRoleIds = overwrites.mapTo(mutableSetOf()) { it.roleId }
        if (targetRoleIds.size != overwrites.size) {
            throw ExpectedException("초기 채널 역할 정책에 같은 역할을 중복 지정할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        if (targetRoleIds.isNotEmpty() &&
            roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, targetRoleIds).size != targetRoleIds.size
        ) {
            throw ExpectedException("초기 채널 역할 정책은 같은 팀의 활성 역할만 지정할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        if (membership.role !in BYPASS_ROLES) {
            val fence = memberTombstoneRepository.findById(TeamRoleMemberTombstone.key(teamId, userId)).orElse(null)
            val roleIds = assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(teamId, userId)
                .filter { fence == null || it.sourceOccurredAt.isAfter(fence.sourceOccurredAt) }
                .mapTo(mutableSetOf()) { it.roleId }
            val roles = loadRoles(teamId, roleIds)
            if (overwrites.any { overwrite ->
                    overwrite.permissions.keys.any { key -> roles.values.none { it.permissions[key] == true } }
                }
            ) {
                throw ExpectedException("초기 채널 역할 정책에는 자신이 보유한 팀 권한만 지정할 수 있습니다.", HttpStatus.FORBIDDEN)
            }
            if (!isAllowed(channel, roleIds, roles, overwrites.associate { it.roleId to it.permissions })) {
                throw ExpectedException("생성자가 접근할 수 있도록 초기 채널 역할 정책을 지정해야 합니다.", HttpStatus.BAD_REQUEST)
            }
        }
        return overwrites.takeIf { it.isNotEmpty() }?.let {
            InitialChannelRolePolicySeed(userId, membership.sourceOccurredAt, it)
        }
    }

    fun requireRolePolicyManagement(
        teamId: Long,
        userId: Long,
        channel: Channel,
        targetRoleId: Long,
        overwrite: Map<String, Boolean>?,
    ) {
        requireTeamPermission(teamId, userId, "manage_roles")
        requireChannelAccess(teamId, userId, channel)
        val membership = membershipRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (membership.active && membership.role in BYPASS_ROLES) return
        val target = roleRepository.findById(targetRoleId).orElse(null)
        if (target == null || target.teamId != teamId || target.deleted) {
            throw ExpectedException("팀 역할을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val fence = memberTombstoneRepository.findById(TeamRoleMemberTombstone.key(teamId, userId)).orElse(null)
        val roleIds = assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(teamId, userId)
            .filter { fence == null || it.sourceOccurredAt.isAfter(fence.sourceOccurredAt) }
            .mapTo(mutableSetOf()) { it.roleId }
        val roles = loadRoles(teamId, roleIds)
        val highestPriority = roles.values.filterNot { it.isDefault }.maxOfOrNull { it.priority }
        if (highestPriority == null || (!target.isDefault && target.priority >= highestPriority)) {
            throw ExpectedException("자신보다 낮은 역할의 채널 권한만 변경할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        val policies = loadPolicies(teamId, setOf(channel.id), roles.keys + targetRoleId)[channel.id].orEmpty()
        val currentOverwrite = policies[targetRoleId].orEmpty()
        val targetBase = permissions(target.permissionsJson)
        val granted = ChannelRolePolicyPermissionSchema.KEYS.filter { key ->
            overwrite?.get(key) ?: (currentOverwrite[key] == false || (overwrite == null && targetBase[key] == true))
        }
        if (granted.any { !isAllowed(channel, roleIds, roles, policies, it) }) {
            throw ExpectedException("자신에게 없는 채널 권한을 부여할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
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
                    policy.roleId to permissions(policy.permissionsJson)
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
        val applicable = roles.filter { (roleId, role) -> role.isDefault || roleId in roleIds }
        val accessPermission = if (channel.type == ChannelType.VOICE) "voice_connect" else "message_read"
        fun allows(key: String): Boolean {
            var allowed = !((channel.isPrivate) && key in ACCESS_PERMISSIONS) &&
                applicable.values.any { it.permissions[key] == true }
            applicable.filterValues { it.isDefault }.forEach { (roleId, _) ->
                policies[roleId]?.get(key)?.let { allowed = it }
            }
            val overrides = applicable.filterValues { !it.isDefault }.keys.mapNotNull { policies[it]?.get(key) }
            if (false in overrides) allowed = false
            if (true in overrides) allowed = true
            return allowed
        }
        return allows(accessPermission) && (permission == null || allows(permission))
    }

    private fun permissions(json: String?): Map<String, Boolean> = json?.let {
        objectMapper.readValue(it, object : TypeReference<Map<String, Boolean>>() {})
    }.orEmpty()

    private companion object {
        val BYPASS_ROLES = setOf("OWNER", "ADMIN")
        val ACCESS_PERMISSIONS = setOf("message_read", "voice_connect")
    }
}
