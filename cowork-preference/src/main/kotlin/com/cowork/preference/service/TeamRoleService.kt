package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.vertx.sqlclient.SqlClient
import java.time.Instant
import java.time.temporal.ChronoUnit

class TeamRoleService(
    private val repository: TeamRoleRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val channelRolePolicyRepository: ChannelRolePolicyRepository,
) {

    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    suspend fun createRole(
        teamId: Long,
        name: String,
        colorHex: String,
        priority: Int,
        mentionable: Boolean,
        permissions: Set<String>,
    ): Result<TeamRoleDefinition> {
        validateName(name).onFailure { return Result.failure(it) }
        validateColor(colorHex).onFailure { return Result.failure(it) }
        if (repository.findRoleByName(teamId, name) != null) {
            return Result.failure(IllegalStateException("Role '$name' already exists"))
        }
        val role = outboxRepository.inTransaction { connection ->
            val persisted = repository.insertRole(
                connection,
                teamId,
                name,
                colorHex,
                priority,
                mentionable,
                permissions,
            )
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.roleUpserted(
                    persisted,
                    requireNotNull(persisted.updatedAt ?: persisted.createdAt).toInstant(),
                ),
            )
            persisted
        }
        return Result.success(role)
    }

    suspend fun updateRole(
        teamId: Long,
        roleId: Long,
        name: String?,
        colorHex: String?,
        priority: Int?,
        mentionable: Boolean?,
        permissions: Set<String>?,
    ): Result<TeamRoleDefinition> {
        val existing = repository.findRole(teamId, roleId)
            ?: return Result.failure(NoSuchElementException("Role '$roleId' not found"))
        val nextName = name ?: existing.name
        val nextColorHex = colorHex ?: existing.colorHex
        validateName(nextName).onFailure { return Result.failure(it) }
        validateColor(nextColorHex).onFailure { return Result.failure(it) }
        if (repository.findRoleByNameExceptId(teamId, nextName, roleId) != null) {
            return Result.failure(IllegalStateException("Role '$nextName' already exists"))
        }

        val role = outboxRepository.inTransaction { connection ->
            val persisted = repository.updateRole(
                client = connection,
                roleId = roleId,
                name = nextName,
                colorHex = nextColorHex,
                priority = priority ?: existing.priority,
                mentionable = mentionable ?: existing.mentionable,
                permissions = permissions ?: existing.permissions,
            )
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.roleUpserted(
                    persisted,
                    requireNotNull(persisted.updatedAt ?: persisted.createdAt).toInstant(),
                ),
            )
            persisted
        }
        return Result.success(role)
    }

    suspend fun deleteRole(teamId: Long, roleId: Long): Result<Unit> {
        if (repository.findRole(teamId, roleId) == null) {
            return runCatching {
                outboxRepository.inTransaction { connection ->
                    val tombstone = repository.deleteRoleWithTombstone(
                        connection,
                        teamId,
                        roleId,
                        Instant.now(),
                    )
                    outboxRepository.enqueue(
                        connection,
                        PreferenceEvents.roleDeleted(teamId, roleId, tombstone.stateOccurredAt),
                    )
                }
            }
                .fold(
                    onSuccess = { Result.failure(NoSuchElementException("Role '$roleId' not found")) },
                    onFailure = { Result.failure(it) },
                )
        }
        val occurredAt = Instant.now()

        return runCatching {
            outboxRepository.inTransaction { connection ->
                val assignments = repository.findMemberRoles(connection, teamId).filter { it.roleId == roleId }
                val policyEvents = deleteChannelRolePoliciesForRole(connection, teamId, roleId)
                val assignmentTombstones = assignments.map { assignment ->
                    repository.deleteAssignment(
                        connection,
                        assignment.accountId,
                        assignment.teamId,
                        assignment.roleId,
                        occurredAt,
                    )
                }
                val roleTombstone = repository.deleteRoleWithTombstone(connection, teamId, roleId, occurredAt)
                outboxRepository.enqueueAll(
                    connection,
                    policyEvents + assignmentTombstones.map { tombstone ->
                        PreferenceEvents.assignmentDeleted(
                            AccountTeamRole(tombstone.accountId, tombstone.teamId, tombstone.roleId),
                            tombstone.stateOccurredAt,
                        )
                    } + PreferenceEvents.roleDeleted(teamId, roleId, roleTombstone.stateOccurredAt),
                )
            }
        }
    }

    suspend fun assignRole(
        accountId: Long,
        teamId: Long,
        roleId: Long,
        sourceMembershipVersion: Instant,
    ): Result<TeamRoleDefinition> {
        repository.findRole(teamId, roleId)
            ?: return Result.failure(NoSuchElementException("Role '$roleId' not found"))
        val role = outboxRepository.inTransaction { connection ->
            val persistedRole = repository.assignRole(
                connection,
                accountId,
                teamId,
                roleId,
                sourceMembershipVersion,
            )
            val assignment = requireNotNull(repository.findAssignment(connection, accountId, teamId, roleId))
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.assignmentUpserted(assignment, requireNotNull(assignment.updatedAt).toInstant()),
            )
            persistedRole
        }
        return Result.success(role)
    }

    suspend fun removeRole(accountId: Long, teamId: Long, roleId: Long) {
        outboxRepository.inTransaction { connection ->
            val tombstone = repository.deleteAssignment(
                connection,
                accountId,
                teamId,
                roleId,
                Instant.now(),
            )
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.assignmentDeleted(
                    AccountTeamRole(accountId, teamId, roleId),
                    tombstone.stateOccurredAt,
                ),
            )
        }
    }

    suspend fun removeMemberRolesAtOrBefore(accountId: Long, teamId: Long, occurredAt: Instant) {
        outboxRepository.inTransaction { connection ->
            removeMemberRolesAtOrBefore(connection, accountId, teamId, occurredAt)
        }
    }

    internal suspend fun removeMemberRolesAtOrBefore(
        client: SqlClient,
        accountId: Long,
        teamId: Long,
        occurredAt: Instant,
    ) {
        val removedAssignments = repository.removeMemberRolesAtOrBefore(client, accountId, teamId, occurredAt)
        val tombstoneVersion = nextProjectionVersion(
            sequenceOf(occurredAt) + removedAssignments.asSequence().mapNotNull { it.updatedAt?.toInstant() },
        )
        val memberFence = repository.upsertMemberFence(client, teamId, accountId, tombstoneVersion)
        val assignmentTombstones = removedAssignments.map { assignment ->
            repository.deleteAssignment(
                client,
                assignment.accountId,
                assignment.teamId,
                assignment.roleId,
                memberFence.stateOccurredAt,
            )
        }
        outboxRepository.enqueueAll(
            client,
            assignmentTombstones.map { tombstone ->
                PreferenceEvents.assignmentDeleted(
                    AccountTeamRole(tombstone.accountId, tombstone.teamId, tombstone.roleId),
                    tombstone.stateOccurredAt,
                )
            } + PreferenceEvents.memberAssignmentsDeleted(teamId, accountId, memberFence.stateOccurredAt),
        )
    }

    suspend fun deleteTeamRoles(teamId: Long, occurredAt: Instant) {
        outboxRepository.inTransaction { connection ->
            deleteTeamRoles(connection, teamId, occurredAt)
        }
    }

    internal suspend fun deleteTeamRoles(client: SqlClient, teamId: Long, occurredAt: Instant) {
        repository.markTeamDeleted(client, teamId, occurredAt)
        val assignments = repository.findMemberRoles(client, teamId)
        val roles = repository.findRoles(client, teamId)
        val policyEvents = deleteChannelRolePoliciesForTeam(client, teamId)
        val memberFences = assignments.groupBy(AccountTeamRole::accountId).mapValues { (accountId, memberAssignments) ->
            val minimum = nextProjectionVersion(
                sequenceOf(occurredAt) + memberAssignments.asSequence().mapNotNull { it.updatedAt?.toInstant() },
            )
            repository.upsertMemberFence(client, teamId, accountId, minimum)
        }
        val assignmentTombstones = assignments.map { assignment ->
            repository.deleteAssignment(
                client,
                assignment.accountId,
                assignment.teamId,
                assignment.roleId,
                requireNotNull(memberFences[assignment.accountId]).stateOccurredAt,
            )
        }
        val roleTombstones = roles.map { role ->
            repository.deleteRoleWithTombstone(client, teamId, role.id, occurredAt)
        }
        outboxRepository.enqueueAll(
            client,
            policyEvents + assignmentTombstones.map { tombstone ->
                PreferenceEvents.assignmentDeleted(
                    AccountTeamRole(tombstone.accountId, tombstone.teamId, tombstone.roleId),
                    tombstone.stateOccurredAt,
                )
            } + memberFences.values.map { fence ->
                PreferenceEvents.memberAssignmentsDeleted(fence.teamId, fence.accountId, fence.stateOccurredAt)
            } + roleTombstones.map { tombstone ->
                PreferenceEvents.roleDeleted(tombstone.teamId, tombstone.roleId, tombstone.stateOccurredAt)
            },
        )
    }

    private suspend fun deleteChannelRolePoliciesForRole(client: SqlClient, teamId: Long, roleId: Long) =
        channelRolePolicyRepository.findPoliciesByRole(client, teamId, roleId).map { policy ->
            deleteChannelRolePolicy(client, policy.teamId, policy.channelId, policy.roleId)
        }

    private suspend fun deleteChannelRolePoliciesForTeam(client: SqlClient, teamId: Long) =
        channelRolePolicyRepository.findPoliciesByTeam(client, teamId).map { policy ->
            deleteChannelRolePolicy(client, policy.teamId, policy.channelId, policy.roleId)
        }

    private suspend fun deleteChannelRolePolicy(
        client: SqlClient,
        teamId: Long,
        channelId: Long,
        roleId: Long,
    ): PreferenceEvent {
        val tombstone = channelRolePolicyRepository.deletePolicy(client, teamId, channelId, roleId)
        return PreferenceEvents.channelRolePolicyState(
            teamId = tombstone.teamId,
            channelId = tombstone.channelId,
            roleId = tombstone.roleId,
            permissions = null,
            occurredAt = tombstone.stateOccurredAt,
            snapshot = false,
        )
    }

    private fun validateName(name: String): Result<Unit> = if (name.isBlank() || name.length > 50) {
        Result.failure(IllegalArgumentException("Role name must be 1 to 50 characters"))
    } else {
        Result.success(Unit)
    }

    private fun validateColor(colorHex: String): Result<Unit> = if (!colorPattern.matches(colorHex)) {
        Result.failure(IllegalArgumentException("Role color must be #RRGGBB"))
    } else {
        Result.success(Unit)
    }

    private fun nextProjectionVersion(versions: Sequence<Instant>): Instant = requireNotNull(versions.maxOrNull())
        .truncatedTo(ChronoUnit.MICROS)
        .plus(1, ChronoUnit.MICROS)
}
