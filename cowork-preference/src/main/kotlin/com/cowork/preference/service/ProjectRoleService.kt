package com.cowork.preference.service

import com.cowork.preference.domain.AccountProjectRole
import com.cowork.preference.domain.ProjectRoleDefinition
import com.cowork.preference.repository.ProjectRoleRepository
import io.vertx.core.json.JsonObject

class ProjectRoleService(private val repository: ProjectRoleRepository) {

    suspend fun getRoles(projectId: Long): List<ProjectRoleDefinition> =
        repository.findRoles(projectId)

    suspend fun createRole(projectId: Long, roleName: String, permissions: JsonObject): Result<ProjectRoleDefinition> {
        val existing = repository.findRole(projectId, roleName)
        if (existing != null) return Result.failure(IllegalStateException("Role '$roleName' already exists"))
        repository.insertRole(projectId, roleName, permissions)
        return Result.success(ProjectRoleDefinition(projectId, roleName, permissions))
    }

    suspend fun deleteRole(projectId: Long, roleName: String): Result<Unit> {
        val existing = repository.findRole(projectId, roleName)
            ?: return Result.failure(NoSuchElementException("Role '$roleName' not found"))
        repository.deleteRole(projectId, roleName)
        return Result.success(Unit)
    }

    suspend fun getMemberRoles(projectId: Long): List<AccountProjectRole> =
        repository.findMemberRoles(projectId)

    suspend fun assignRole(accountId: Long, projectId: Long, roleName: String): Result<Unit> {
        val role = repository.findRole(projectId, roleName)
            ?: return Result.failure(NoSuchElementException("Role '$roleName' not found"))
        repository.assignRole(accountId, projectId, roleName)
        return Result.success(Unit)
    }

    suspend fun removeRole(accountId: Long, projectId: Long, roleName: String) {
        repository.removeRole(accountId, projectId, roleName)
    }
}
