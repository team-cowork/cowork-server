package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.repository.TeamRoleRepository

class TeamRoleService(private val repository: TeamRoleRepository) {

    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    suspend fun getRoles(teamId: Long): List<TeamRoleDefinition> =
        repository.findRoles(teamId)

    suspend fun getMemberRoles(teamId: Long): List<AccountTeamRole> =
        repository.findMemberRoles(teamId)

    suspend fun getMemberRoleDefinitions(teamId: Long, accountId: Long): List<TeamRoleDefinition> =
        repository.findMemberRoleDefinitions(teamId, accountId)

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
        return Result.success(repository.insertRole(teamId, name, colorHex, priority, mentionable, permissions))
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

        return Result.success(
            repository.updateRole(
                roleId = roleId,
                name = nextName,
                colorHex = nextColorHex,
                priority = priority ?: existing.priority,
                mentionable = mentionable ?: existing.mentionable,
                permissions = permissions ?: existing.permissions,
            )
        )
    }

    suspend fun deleteRole(teamId: Long, roleId: Long): Result<Unit> {
        repository.findRole(teamId, roleId)
            ?: return Result.failure(NoSuchElementException("Role '$roleId' not found"))
        repository.deleteRole(teamId, roleId)
        return Result.success(Unit)
    }

    suspend fun assignRole(accountId: Long, teamId: Long, roleId: Long): Result<TeamRoleDefinition> {
        repository.findRole(teamId, roleId)
            ?: return Result.failure(NoSuchElementException("Role '$roleId' not found"))
        return Result.success(repository.assignRole(accountId, teamId, roleId))
    }

    suspend fun removeRole(accountId: Long, teamId: Long, roleId: Long) {
        repository.removeRole(accountId, teamId, roleId)
    }

    suspend fun removeMemberRoles(accountId: Long, teamId: Long) {
        repository.removeMemberRoles(accountId, teamId)
    }

    private fun validateName(name: String): Result<Unit> =
        if (name.isBlank() || name.length > 50) {
            Result.failure(IllegalArgumentException("Role name must be 1 to 50 characters"))
        } else {
            Result.success(Unit)
        }

    private fun validateColor(colorHex: String): Result<Unit> =
        if (!colorPattern.matches(colorHex)) {
            Result.failure(IllegalArgumentException("Role color must be #RRGGBB"))
        } else {
            Result.success(Unit)
        }
}
