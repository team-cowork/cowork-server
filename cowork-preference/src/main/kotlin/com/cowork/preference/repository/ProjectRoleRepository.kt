package com.cowork.preference.repository

import com.cowork.preference.domain.AccountProjectRole
import com.cowork.preference.domain.ProjectRoleDefinition
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple

class ProjectRoleRepository(private val pool: Pool) {

    suspend fun findRoles(projectId: Long): List<ProjectRoleDefinition> {
        val rows = pool.preparedQuery(
            "SELECT project_id, role_name, permissions FROM project_role_definition WHERE project_id = \$1"
        ).execute(Tuple.of(projectId)).coAwait()
        return rows.map {
            ProjectRoleDefinition(
                projectId = it.getLong("project_id"),
                roleName = it.getString("role_name"),
                permissions = it.getJsonObject("permissions"),
            )
        }
    }

    suspend fun findRole(projectId: Long, roleName: String): ProjectRoleDefinition? {
        val rows = pool.preparedQuery(
            "SELECT project_id, role_name, permissions FROM project_role_definition WHERE project_id = \$1 AND role_name = \$2"
        ).execute(Tuple.of(projectId, roleName)).coAwait()
        return rows.firstOrNull()?.let {
            ProjectRoleDefinition(
                projectId = it.getLong("project_id"),
                roleName = it.getString("role_name"),
                permissions = it.getJsonObject("permissions"),
            )
        }
    }

    suspend fun insertRole(projectId: Long, roleName: String, permissions: JsonObject) {
        pool.preparedQuery(
            "INSERT INTO project_role_definition (project_id, role_name, permissions) VALUES (\$1, \$2, \$3::jsonb)"
        ).execute(Tuple.of(projectId, roleName, permissions)).coAwait()
    }

    suspend fun deleteRole(projectId: Long, roleName: String) {
        pool.preparedQuery(
            "DELETE FROM project_role_definition WHERE project_id = \$1 AND role_name = \$2"
        ).execute(Tuple.of(projectId, roleName)).coAwait()
    }

    suspend fun findMemberRoles(projectId: Long): List<AccountProjectRole> {
        val rows = pool.preparedQuery(
            "SELECT account_id, project_id, role_name FROM account_project_role WHERE project_id = \$1"
        ).execute(Tuple.of(projectId)).coAwait()
        return rows.map {
            AccountProjectRole(
                accountId = it.getLong("account_id"),
                projectId = it.getLong("project_id"),
                roleName = it.getString("role_name"),
            )
        }
    }

    suspend fun assignRole(accountId: Long, projectId: Long, roleName: String) {
        pool.preparedQuery(
            "INSERT INTO account_project_role (account_id, project_id, role_name) VALUES (\$1, \$2, \$3) ON CONFLICT DO NOTHING"
        ).execute(Tuple.of(accountId, projectId, roleName)).coAwait()
    }

    suspend fun removeRole(accountId: Long, projectId: Long, roleName: String) {
        pool.preparedQuery(
            "DELETE FROM account_project_role WHERE account_id = \$1 AND project_id = \$2 AND role_name = \$3"
        ).execute(Tuple.of(accountId, projectId, roleName)).coAwait()
    }
}
