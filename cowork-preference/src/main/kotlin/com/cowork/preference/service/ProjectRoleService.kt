package com.cowork.preference.service

import com.cowork.preference.domain.AccountProjectRole
import com.cowork.preference.domain.ProjectRoleDefinition
import com.cowork.preference.repository.InitialResourceAccessRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectAccessStateRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.core.json.JsonObject

class ProjectRoleService(
    private val repository: ProjectRoleRepository,
    private val outbox: PreferenceOutboxRepository,
    private val access: ProjectAccessStateRepository,
    private val initialAccess: InitialResourceAccessRepository,
    private val guard: ProjectAccessGuard,
) {
    suspend fun getRoles(projectId: Long): List<ProjectRoleDefinition> = repository.findRoles(projectId)

    suspend fun createRole(
        projectId: Long,
        roleName: String,
        permissions: JsonObject,
        requester: RequesterContext,
        priority: Int = 0,
    ): Result<ProjectRoleDefinition> = runCatching {
        require(roleName.isNotBlank() && roleName.length <= 50) { "Invalid roleName" }
        require(permissions.fieldNames().all { it in setOf("read", "write", "manage", "reorder") && permissions.getValue(it) is Boolean }) {
            "Project permissions must use read, write, manage or reorder boolean values"
        }
        val canonical = permissions.copy()
        outbox.inTransaction { client ->
            guard.requireRoleCreation(projectId, requester, canonical, client)
            check(repository.findRole(projectId, roleName, client) == null) { "Role already exists" }
            repository.insertRole(projectId, roleName, canonical, priority, client)
            initialAccess.claim(client, "PROJECT", projectId)
            Unit
        }
        ProjectRoleDefinition(projectId, roleName, canonical, priority)
    }

    suspend fun deleteRole(projectId: Long, roleName: String, requester: RequesterContext): Result<Unit> = runCatching {
        outbox.inTransaction { client ->
            guard.requireRoleDeletion(projectId, requester, roleName, client)
            repository.findRole(projectId, roleName, client) ?: throw NoSuchElementException("Role not found")
            repository.deleteRole(projectId, roleName, client)
            initialAccess.claim(client, "PROJECT", projectId)
            access.refresh(client, projectId)
        }
    }

    suspend fun getMemberRoles(projectId: Long): List<AccountProjectRole> = repository.findMemberRoles(projectId)

    suspend fun assignRole(accountId: Long, projectId: Long, roleName: String, requester: RequesterContext): Result<Unit> = runCatching {
        outbox.inTransaction { client ->
            guard.requireRoleAssignment(projectId, requester, roleName, accountId, client)
            repository.findRole(projectId, roleName, client) ?: throw NoSuchElementException("Role not found")
            repository.assignRole(accountId, projectId, roleName, client)
            initialAccess.claim(client, "PROJECT", projectId)
            access.refresh(client, projectId)
        }
    }

    suspend fun removeRole(accountId: Long, projectId: Long, roleName: String, requester: RequesterContext) {
        outbox.inTransaction { client ->
            guard.requireRoleRemoval(projectId, requester, roleName, accountId, client)
            repository.removeRole(accountId, projectId, roleName, client)
            initialAccess.claim(client, "PROJECT", projectId)
            access.refresh(client, projectId)
        }
    }
}
