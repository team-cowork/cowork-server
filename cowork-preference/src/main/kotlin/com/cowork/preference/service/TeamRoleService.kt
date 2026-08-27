package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.vertx.sqlclient.SqlClient
import java.time.Instant
import java.time.ZoneOffset

class TeamRoleService(
    private val repository: TeamRoleRepository,
    private val outboxRepository: PreferenceOutboxRepository,
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
                    outboxRepository.enqueue(connection, PreferenceEvents.roleDeleted(teamId, roleId, Instant.now()))
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
                repository.deleteRole(connection, teamId, roleId)
                outboxRepository.enqueueAll(
                    connection,
                    assignments.map { PreferenceEvents.assignmentDeleted(it, occurredAt) } +
                        PreferenceEvents.roleDeleted(teamId, roleId, occurredAt),
                )
            }
        }
    }

    suspend fun assignRole(accountId: Long, teamId: Long, roleId: Long): Result<TeamRoleDefinition> {
        repository.findRole(teamId, roleId)
            ?: return Result.failure(NoSuchElementException("Role '$roleId' not found"))
        val role = outboxRepository.inTransaction { connection ->
            val persistedRole = repository.assignRole(connection, accountId, teamId, roleId)
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
            repository.removeRole(connection, accountId, teamId, roleId)
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.assignmentDeleted(AccountTeamRole(accountId, teamId, roleId), Instant.now()),
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
        repository.removeMemberRolesAtOrBefore(client, accountId, teamId, occurredAt.atOffset(ZoneOffset.UTC))
        outboxRepository.enqueue(client, PreferenceEvents.memberAssignmentsDeleted(teamId, accountId, occurredAt))
    }

    suspend fun deleteTeamRoles(teamId: Long, occurredAt: Instant) {
        outboxRepository.inTransaction { connection ->
            deleteTeamRoles(connection, teamId, occurredAt)
        }
    }

    internal suspend fun deleteTeamRoles(client: SqlClient, teamId: Long, occurredAt: Instant) {
        val assignments = repository.findMemberRoles(client, teamId)
        val roles = repository.findRoles(client, teamId)

        repository.deleteTeamRoles(client, teamId)
        outboxRepository.enqueueAll(
            client,
            assignments.map { PreferenceEvents.assignmentDeleted(it, occurredAt) } +
                roles.map { PreferenceEvents.roleDeleted(teamId, it.id, occurredAt) },
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
}
