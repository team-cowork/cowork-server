package com.cowork.preference.service

import com.cowork.preference.domain.AccountProjectRole
import com.cowork.preference.domain.ProjectRoleDefinition
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectAccessStateRepository
import com.cowork.preference.repository.ProjectRoleRepository
import io.vertx.core.json.JsonObject

class ProjectRoleService(
    private val repository: ProjectRoleRepository,
    private val outbox: PreferenceOutboxRepository,
    private val access: ProjectAccessStateRepository,
) {
    suspend fun getRoles(projectId: Long): List<ProjectRoleDefinition> = repository.findRoles(projectId)

    suspend fun createRole(projectId: Long, roleName: String, permissions: JsonObject, priority: Int = 0): Result<ProjectRoleDefinition> = runCatching {
        require(roleName.isNotBlank() && roleName.length <= 50) { "Invalid roleName" }
        require(permissions.fieldNames().all { it in setOf("read", "write", "manage", "reorder") && permissions.getValue(it) is Boolean }) {
            "Project permissions must use read, write, manage or reorder boolean values"
        }
        check(repository.findRole(projectId, roleName) == null) { "Role already exists" }
        val canonical = permissions.copy()
        outbox.inTransaction { client -> repository.insertRole(projectId, roleName, canonical, priority, client) }
        ProjectRoleDefinition(projectId, roleName, canonical, priority)
    }

    suspend fun deleteRole(projectId: Long, roleName: String): Result<Unit> = runCatching {
        repository.findRole(projectId, roleName) ?: throw NoSuchElementException("Role not found")
        outbox.inTransaction { client ->
            repository.deleteRole(projectId, roleName, client)
            access.refresh(client, projectId)
        }
    }

    suspend fun getMemberRoles(projectId: Long): List<AccountProjectRole> = repository.findMemberRoles(projectId)

    suspend fun assignRole(accountId: Long, projectId: Long, roleName: String): Result<Unit> = runCatching {
        repository.findRole(projectId, roleName) ?: throw NoSuchElementException("Role not found")
        outbox.inTransaction { client ->
            repository.assignRole(accountId, projectId, roleName, client)
            access.refresh(client, projectId)
        }
    }

    suspend fun removeRole(accountId: Long, projectId: Long, roleName: String) {
        outbox.inTransaction { client ->
            repository.removeRole(accountId, projectId, roleName, client)
            access.refresh(client, projectId)
        }
    }
}
