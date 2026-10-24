package com.cowork.preference.repository

import com.cowork.preference.domain.AccountProjectRole
import com.cowork.preference.domain.ProjectRoleDefinition
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class ProjectRoleRepository(private val pool: Pool) {
    suspend fun canRead(projectId: Long, accountId: Long): Boolean {
        val rows = pool.preparedQuery("SELECT can_read FROM tb_project_access_states WHERE project_id = \$1 AND account_id = \$2")
            .execute(Tuple.of(projectId, accountId)).coAwait()
        return rows.firstOrNull()?.getBoolean("can_read") == true
    }

    suspend fun findRoles(projectId: Long): List<ProjectRoleDefinition> {
        val rows = pool.preparedQuery(
            "SELECT project_id, role_name, permissions, priority FROM project_role_definition WHERE project_id = \$1"
        ).execute(Tuple.of(projectId)).coAwait()
        return rows.map {
            ProjectRoleDefinition(
                projectId = it.getLong("project_id"),
                roleName = it.getString("role_name"),
                permissions = it.getJsonObject("permissions"),
                priority = it.getInteger("priority"),
            )
        }
    }

    suspend fun findRole(projectId: Long, roleName: String): ProjectRoleDefinition? {
        val rows = pool.preparedQuery(
            "SELECT project_id, role_name, permissions, priority FROM project_role_definition WHERE project_id = \$1 AND role_name = \$2"
        ).execute(Tuple.of(projectId, roleName)).coAwait()
        return rows.firstOrNull()?.let {
            ProjectRoleDefinition(
                projectId = it.getLong("project_id"),
                roleName = it.getString("role_name"),
                permissions = it.getJsonObject("permissions"),
                priority = it.getInteger("priority"),
            )
        }
    }

    suspend fun insertRole(projectId: Long, roleName: String, permissions: JsonObject, priority: Int, client: SqlClient = pool) {
        client.preparedQuery(
            "INSERT INTO project_role_definition (project_id, role_name, permissions, priority) VALUES (\$1, \$2, \$3::jsonb, \$4)"
        ).execute(Tuple.of(projectId, roleName, permissions, priority)).coAwait()
    }

    suspend fun deleteRole(projectId: Long, roleName: String, client: SqlClient = pool) {
        client.preparedQuery(
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

    suspend fun assignRole(accountId: Long, projectId: Long, roleName: String, client: SqlClient = pool) {
        val member = client.preparedQuery(
            "SELECT m.account_id FROM tb_project_lifecycle_projections p JOIN tb_team_member_projections m ON m.team_id = p.team_id " +
                "WHERE p.project_id = \$1 AND m.account_id = \$2 AND NOT p.deleted AND NOT m.deleted FOR UPDATE OF p FOR SHARE OF m",
        ).execute(Tuple.of(projectId, accountId)).coAwait()
        require(member.firstOrNull() != null) { "Target must be an active team member" }
        client.preparedQuery(
            "INSERT INTO account_project_role (account_id, project_id, role_name) VALUES (\$1, \$2, \$3) ON CONFLICT DO NOTHING"
        ).execute(Tuple.of(accountId, projectId, roleName)).coAwait()
    }

    suspend fun removeRole(accountId: Long, projectId: Long, roleName: String, client: SqlClient = pool) {
        client.preparedQuery(
            "DELETE FROM account_project_role WHERE account_id = \$1 AND project_id = \$2 AND role_name = \$3"
        ).execute(Tuple.of(accountId, projectId, roleName)).coAwait()
    }
}
