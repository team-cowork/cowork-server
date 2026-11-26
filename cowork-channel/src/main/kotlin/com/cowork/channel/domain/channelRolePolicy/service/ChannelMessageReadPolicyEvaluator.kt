package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.event.InitialChannelRoleOverwrite
import com.cowork.channel.domain.channel.event.InitialChannelRolePolicySeed
import com.cowork.channel.domain.channelRolePolicy.access.ChannelAccessProjection
import com.cowork.channel.domain.channelRolePolicy.access.ChannelAccessProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstone
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.policy.PermissionKeys
import com.cowork.channel.global.policy.TeamAccessProjection
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class ChannelMessageReadPolicyEvaluator(
    private val teamMembershipRepository: TeamMembershipRepository,
    private val teamRoleAssignmentProjectionRepository: TeamRoleAssignmentProjectionRepository,
    private val teamRoleMemberTombstoneRepository: TeamRoleMemberTombstoneRepository,
    private val teamRoleProjectionRepository: TeamRoleProjectionRepository,
    private val channelRolePolicyProjectionRepository: ChannelRolePolicyProjectionRepository,
    private val channelAccessProjectionRepository: ChannelAccessProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
    private val objectMapper: ObjectMapper,
    private val teamAccessProjection: TeamAccessProjection,
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

    fun requirePrivateTransitionAccess(teamId: Long, userId: Long, channel: Channel) {
        projectionReadinessGate.requireReady()
        if (channel.id !in readableChannelIds(teamId, userId, listOf(channel), privateTransition = true)) {
            throw ExpectedException(
                "비공개 전환 후에도 접근할 수 있도록 채널 역할 권한을 먼저 설정해야 합니다.",
                HttpStatus.BAD_REQUEST,
            )
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
        val membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        val allowed = readableChannelIds(teamId, userId, channels, "channel_reorder")
        if (channels.any { it.id !in allowed }) {
            throw ExpectedException("채널 재정렬 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    /**
     * Preference가 발행한 판정 결과로 접근할 수 있는 채널을 고른다. [permission]이 있으면 그 권한도 요구한다.
     * [privateTransition]이 true면 채널이 비공개일 때 접근할 수 있는지를 본다.
     */
    private fun readableChannelIds(
        teamId: Long,
        userId: Long,
        channels: List<Channel>,
        permission: String? = null,
        privateTransition: Boolean = false,
    ): Set<Long> {
        if (channels.isEmpty()) return emptySet()
        val membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)
            ?.takeIf { it.active }
            ?: return emptySet()
        val decisions = channelAccessProjectionRepository.findAllById(
            channels.map { ChannelAccessProjection.key(it.id, userId) },
        ).associateBy { it.channelId }
        return channels.filter { channel ->
            val decision = decisions[channel.id] ?: return@filter false
            if (privateTransition) {
                decision.isCurrentFor(teamId, membership.authorizationVersion) && decision.canReadPrivate
            } else {
                allows(teamId, channel, decision, membership, permission)
            }
        }.mapTo(mutableSetOf()) { it.id }
    }

    fun readableUserIds(channel: Channel, memberships: List<TeamMembership>): Set<Long> {
        projectionReadinessGate.requireReady()
        val teamId = channel.teamId ?: return emptySet()
        val activeMembers = memberships.filter { it.teamId == teamId && it.active }.associateBy { it.userId }
        if (activeMembers.isEmpty()) return emptySet()
        return channelAccessProjectionRepository.findAllById(
            activeMembers.keys.map { ChannelAccessProjection.key(channel.id, it) },
        ).filter { allows(teamId, channel, it, activeMembers.getValue(it.userId)) }
            .mapTo(mutableSetOf()) { it.userId }
    }

    /**
     * 채널 접근 권한이 없으면 Preference가 다른 권한도 거부하지만, 접근 권한을 함께 확인한다.
     * 비공개 전환은 인가 version을 바꾸지 않으므로, 공개 기준 판정이 남아 있어도 비공개 채널은 [ChannelAccessProjection.canReadPrivate]를 요구한다.
     */
    private fun allows(
        teamId: Long,
        channel: Channel,
        decision: ChannelAccessProjection,
        membership: TeamMembership,
        permission: String? = null,
    ): Boolean {
        if (!decision.isCurrentFor(teamId, membership.authorizationVersion)) return false
        val accessPermission = if (channel.type == ChannelType.VOICE) {
            PermissionKeys.VOICE_CONNECT
        } else {
            PermissionKeys.MESSAGE_READ
        }
        return accessPermission in decision.allowedPermissions &&
            (!channel.isPrivate || decision.canReadPrivate) &&
            (permission == null || permission in decision.allowedPermissions)
    }

    private data class RolePermissions(val priority: Int, val isDefault: Boolean, val permissions: Map<String, Boolean>)

    private fun loadRoles(teamId: Long, roleIds: Set<Long>): Map<Long, RolePermissions> {
        val defaults = teamRoleProjectionRepository.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(teamId)
        val assigned = if (roleIds.isEmpty()) {
            emptyList()
        } else {
            teamRoleProjectionRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, roleIds)
        }
        return (defaults + assigned).associate {
            it.roleId to RolePermissions(it.priority, it.isDefault, permissions(it.permissionsJson))
        }
    }

    fun requireTeamPermission(teamId: Long, userId: Long, permission: String) {
        projectionReadinessGate.requireReady()
        requireActiveMember(teamId, userId)
        if (permission !in teamAccessProjection.permissions(teamId, userId)) {
            throw ExpectedException("해당 팀 작업 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    /**
     * 활성 팀 멤버의 관리자 정책 또는 [allowed]가 허용할 때 통과시킨다.
     * 호출자가 projection readiness를 먼저 확인해야 한다.
     */
    fun requireMemberPermission(
        teamId: Long,
        userId: Long,
        deniedMessage: String,
        allowed: (TeamMembership) -> Boolean,
    ) {
        val membership = requireActiveMember(teamId, userId)
        if (hasAdministratorPolicy(teamId, userId)) return
        if (!allowed(membership)) throw ExpectedException(deniedMessage, HttpStatus.FORBIDDEN)
    }

    private fun requireActiveMember(teamId: Long, userId: Long): TeamMembership =
        teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)?.takeIf { it.active }
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

    fun prepareInitialRolePolicySeed(
        teamId: Long,
        userId: Long,
        channel: Channel,
        overwrites: List<InitialChannelRoleOverwrite>,
    ): InitialChannelRolePolicySeed? {
        requireTeamPermission(teamId, userId, "manage_channels")
        val membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)
            ?.takeIf { it.active }
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (overwrites.isNotEmpty() &&
            (!membership.sourceOccurredAt.isAfter(Instant.EPOCH) || membership.authorizationVersion == null)
        ) {
            throw ExpectedException("팀 멤버십 정보 동기화가 필요합니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        val targetRoleIds = overwrites.mapTo(mutableSetOf()) { it.roleId }
        if (targetRoleIds.size != overwrites.size) {
            throw ExpectedException("초기 채널 역할 정책에 같은 역할을 중복 지정할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        if (targetRoleIds.isNotEmpty() &&
            teamRoleProjectionRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, targetRoleIds).size !=
            targetRoleIds.size
        ) {
            throw ExpectedException("초기 채널 역할 정책은 같은 팀의 활성 역할만 지정할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        val permissions = teamAccessProjection.permissions(teamId, userId)
        if (PermissionKeys.ADMINISTRATOR !in permissions) {
            val fence = teamRoleMemberTombstoneRepository.findById(
                TeamRoleMemberTombstone.key(teamId, userId),
            ).orElse(null)
            val roleIds = teamRoleAssignmentProjectionRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(
                teamId,
                userId,
            )
                .filter { fence == null || it.sourceOccurredAt.isAfter(fence.sourceOccurredAt) }
                .mapTo(mutableSetOf()) { it.roleId }
            val roles = loadRoles(teamId, roleIds)
            if (overwrites.any { overwrite ->
                    overwrite.permissions.keys.any { it !in permissions }
                }
            ) {
                throw ExpectedException("초기 채널 역할 정책에는 자신이 보유한 팀 권한만 지정할 수 있습니다.", HttpStatus.FORBIDDEN)
            }
            if (!isAllowed(channel, roleIds, roles, overwrites.associate { it.roleId to it.permissions })) {
                throw ExpectedException("생성자가 접근할 수 있도록 초기 채널 역할 정책을 지정해야 합니다.", HttpStatus.BAD_REQUEST)
            }
        }
        return overwrites.takeIf { it.isNotEmpty() }?.let {
            InitialChannelRolePolicySeed(
                userId,
                membership.sourceOccurredAt,
                it,
                checkNotNull(membership.authorizationVersion),
            )
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
        val membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (hasAdministratorPolicy(teamId, userId)) return
        val target = teamRoleProjectionRepository.findById(targetRoleId).orElse(null)
        if (target == null || target.teamId != teamId || target.deleted) {
            throw ExpectedException("팀 역할을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val fence = teamRoleMemberTombstoneRepository.findById(TeamRoleMemberTombstone.key(teamId, userId)).orElse(null)
        val roleIds = teamRoleAssignmentProjectionRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(teamId, userId)
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
        val granted = PermissionKeys.CHANNEL_SCOPED.filter { key ->
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
        channelRolePolicyProjectionRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(
            teamId,
            channelIds,
            roleIds,
        )
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
        isPrivate: Boolean = channel.isPrivate,
    ): Boolean {
        val applicable = roles.filter { (roleId, role) -> role.isDefault || roleId in roleIds }
        val accessPermission = if (channel.type == ChannelType.VOICE) "voice_connect" else "message_read"
        fun allows(key: String): Boolean {
            var allowed = !(isPrivate && key in ACCESS_PERMISSIONS) &&
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

    internal fun hasAdministratorPolicy(teamId: Long, userId: Long): Boolean =
        PermissionKeys.ADMINISTRATOR in teamAccessProjection.permissions(teamId, userId)

    private fun permissions(json: String?): Map<String, Boolean> = json?.let {
        objectMapper.readValue(it, object : TypeReference<Map<String, Boolean>>() {})
    }.orEmpty()

    private companion object {
        val ACCESS_PERMISSIONS = setOf("message_read", "voice_connect")
    }
}
