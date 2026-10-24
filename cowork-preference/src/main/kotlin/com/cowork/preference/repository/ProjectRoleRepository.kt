package com.cowork.preference.repository

import com.cowork.preference.domain.AccountProjectRole
import com.cowork.preference.domain.ProjectRoleDefinition
import com.cowork.preference.domain.ProjectPermissions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class ProjectRoleRepository(private val pool: Pool) {
    suspend fun effectivePermissions(projectId: Long, accountId: Long, client: SqlClient = pool): ProjectPermissions {
        val rows = client.preparedQuery(
            "SELECT a.can_read, a.can_write, a.can_manage, a.can_reorder FROM tb_project_access_states a " +
                "JOIN tb_project_lifecycle_projections p ON p.project_id = a.project_id AND NOT p.deleted " +
                "JOIN tb_team_member_projections m ON m.team_id = p.team_id AND m.account_id = a.account_id AND NOT m.deleted " +
                "WHERE a.project_id = \$1 AND a.account_id = \$2 " +
                // TODO(#596): 2026-11-01 05:00 KST 이후 구버전 중단·멤버 및 파생 snapshot 보정·구형 재처리 종료 후 fallback을 제거한다.
                // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
                "AND COALESCE(a.source_authorization_version, a.source_membership_version) = " +
                "COALESCE(m.authorization_version, m.source_occurred_at)",
        ).execute(Tuple.of(projectId, accountId)).coAwait()
        val row = rows.firstOrNull() ?: return ProjectPermissions()
        val read = row.getBoolean("can_read")
        return ProjectPermissions(read, read && row.getBoolean("can_write"), read && row.getBoolean("can_manage"), read && row.getBoolean("can_reorder"))
    }

    suspend fun canRead(projectId: Long, accountId: Long): Boolean = effectivePermissions(projectId, accountId).read

    suspend fun canManage(projectId: Long, accountId: Long): Boolean = effectivePermissions(projectId, accountId).manage

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

    suspend fun findRole(projectId: Long, roleName: String, client: SqlClient = pool): ProjectRoleDefinition? {
        val rows = client.preparedQuery(
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

    suspend fun findMemberRoles(projectId: Long, client: SqlClient = pool): List<AccountProjectRole> {
        val rows = client.preparedQuery(
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
            "SELECT m.account_id, m.source_occurred_at FROM tb_project_lifecycle_projections p JOIN tb_team_member_projections m ON m.team_id = p.team_id " +
                "WHERE p.project_id = \$1 AND m.account_id = \$2 AND NOT p.deleted AND NOT m.deleted FOR UPDATE OF p FOR SHARE OF m",
        ).execute(Tuple.of(projectId, accountId)).coAwait()
        val membership = requireNotNull(member.firstOrNull()) { "Target must be an active team member" }
        client.preparedQuery(
            "INSERT INTO account_project_role (account_id, project_id, role_name, source_membership_version) VALUES (\$1, \$2, \$3, \$4) " +
                "ON CONFLICT (account_id, project_id, role_name) DO UPDATE SET " +
                "source_membership_version = GREATEST(account_project_role.source_membership_version, EXCLUDED.source_membership_version)",
        ).execute(Tuple.of(accountId, projectId, roleName, membership.getOffsetDateTime("source_occurred_at"))).coAwait()
    }

    suspend fun removeRole(accountId: Long, projectId: Long, roleName: String, client: SqlClient = pool) {
        client.preparedQuery(
            "DELETE FROM account_project_role WHERE account_id = \$1 AND project_id = \$2 AND role_name = \$3"
        ).execute(Tuple.of(accountId, projectId, roleName)).coAwait()
    }
}
