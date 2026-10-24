package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
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
        val rolesById = roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, assignedRoleIds)
            .associateBy { it.roleId }
        if (rolesById.isEmpty()) return emptySet()
        val activeRoleIds = rolesById.keys
        val policiesByChannel = policyRepository
            .findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(teamId, channelIds, activeRoleIds)
            .groupBy { it.channelId }

        val defaults = rolesById.mapValues { (_, role) -> permissions(role.permissionsJson) }
        return channels.filter { channel ->
            val key = permission ?: if (channel.type == ChannelType.VOICE) "voice_connect" else "message_read"
            val overrides = policiesByChannel[channel.id].orEmpty().associateBy { it.roleId }
            val highestPriority = rolesById.values.maxOf { it.priority }
            rolesById.values.filter { it.priority == highestPriority }.all { role ->
                val policy = overrides[role.roleId]
                val override = permissions(policy?.permissionsJson)[key]
                    ?: if (key == "message_read") policy?.messageRead else null
                override
                    ?: if (channel.isPrivate &&
                        key in ACCESS_PERMISSIONS
                    ) {
                        false
                    } else {
                        defaults.getValue(role.roleId)[key] ?: false
                    }
            }
        }.mapTo(mutableSetOf()) { it.id }
    }

    private fun permissions(json: String?): Map<String, Boolean> = json?.let {
        objectMapper.readValue(it, object : TypeReference<Map<String, Boolean>>() {})
    }.orEmpty()

    private companion object {
        const val OWNER_ROLE = "OWNER"
        val ACCESS_PERMISSIONS = setOf("message_read", "voice_connect")
    }
}
