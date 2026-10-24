package com.cowork.preference.repository

import com.cowork.preference.domain.ProjectPermissions
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.time.ZoneOffset

class ProjectAccessStateRepository(private val outbox: PreferenceOutboxRepository) {
    suspend fun refresh(client: SqlClient, projectId: Long, accountId: Long? = null) {
        // Serialize recomputation so concurrent grants/revocations cannot publish an older decision last.
        val project = client.preparedQuery("SELECT team_id, deleted, is_private FROM tb_project_lifecycle_projections WHERE project_id = \$1 FOR UPDATE")
            .execute(Tuple.of(projectId)).coAwait().firstOrNull() ?: return
        val team = loadTeamAccess(client, project.getLong("team_id"), accountId)
        refreshProject(client, projectId, accountId, project, team)
    }

    private suspend fun refreshProject(
        client: SqlClient,
        projectId: Long,
        accountId: Long?,
        project: Row,
        team: TeamAccess,
    ) {
        val (members, defaults, assigned) = team
        val projectRoles = client.preparedQuery(
            "SELECT a.account_id, d.permissions FROM account_project_role a " +
                "JOIN project_role_definition d USING (project_id, role_name) WHERE a.project_id = \$1 " +
                "AND (\$2::bigint IS NULL OR a.account_id = \$2)",
        ).execute(Tuple.of(projectId, accountId)).coAwait()
            .groupBy({ it.getLong("account_id") }, { it.getJsonObject("permissions") })
        val existing = client.preparedQuery(
            "SELECT * FROM tb_project_access_states WHERE project_id = \$1 AND (\$2::bigint IS NULL OR account_id = \$2)",
        ).execute(Tuple.of(projectId, accountId)).coAwait().associateBy { it.getLong("account_id") }
        (members.keys + existing.keys).sorted().forEach { accountId ->
            val member = members[accountId]
            val permissions = if (project.getBoolean("deleted") || member == null) {
                ProjectPermissions()
            } else {
                ProjectPermissions.resolve(
                    defaults + assigned[accountId].orEmpty(), projectRoles[accountId].orEmpty(),
                    member.getString("built_in_role") in setOf("OWNER", "ADMIN"),
                    isPrivate = project.getBoolean("is_private"),
                )
            }
            val previous = existing[accountId]
            val membershipVersion = member?.getOffsetDateTime("source_occurred_at") ?: Instant.EPOCH.atOffset(ZoneOffset.UTC)
            val authorizationVersion = member?.getOffsetDateTime("authorization_version")
            val previousAuthorizationVersion = previous?.getOffsetDateTime("source_authorization_version")
            // A nullable stamp repair must publish a newer decision even when its effective version is unchanged.
            if (previous != null && previous.permissions() == permissions &&
                previous.getOffsetDateTime("source_membership_version").toInstant() == membershipVersion.toInstant() &&
                previousAuthorizationVersion?.toInstant() == authorizationVersion?.toInstant()
            ) return@forEach
            val rows = client.preparedQuery(
                """
                INSERT INTO tb_project_access_states
                    (project_id, account_id, can_read, can_write, can_manage, can_reorder,
                     source_membership_version, source_authorization_version, state_occurred_at)
                VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8, clock_timestamp())
                ON CONFLICT (project_id, account_id) DO UPDATE SET
                    can_read = EXCLUDED.can_read, can_write = EXCLUDED.can_write,
                    can_manage = EXCLUDED.can_manage, can_reorder = EXCLUDED.can_reorder,
                    source_membership_version = EXCLUDED.source_membership_version,
                    source_authorization_version = EXCLUDED.source_authorization_version,
                    state_occurred_at = GREATEST(clock_timestamp(), tb_project_access_states.state_occurred_at + INTERVAL '1 microsecond')
                RETURNING *
                """.trimIndent(),
            ).execute(Tuple.tuple().addLong(projectId).addLong(accountId).addBoolean(permissions.read).addBoolean(permissions.write)
                .addBoolean(permissions.manage).addBoolean(permissions.reorder).addOffsetDateTime(membershipVersion)
                .addOffsetDateTime(authorizationVersion)).coAwait()
            rows.forEach { outbox.enqueue(client, event(it)) }
        }
    }

    suspend fun refreshTeam(client: SqlClient, teamId: Long, accountId: Long? = null) {
        val projects = client.preparedQuery(
            "SELECT project_id, deleted, is_private FROM tb_project_lifecycle_projections " +
                "WHERE team_id = \$1 ORDER BY project_id FOR UPDATE",
        ).execute(Tuple.of(teamId)).coAwait().toList()
        if (projects.isEmpty()) return
        // The caller's ordered outbox transaction keeps shared team inputs stable across project recomputation.
        val team = loadTeamAccess(client, teamId, accountId)
        projects.forEach { refreshProject(client, it.getLong("project_id"), accountId, it, team) }
    }

    private suspend fun loadTeamAccess(client: SqlClient, teamId: Long, accountId: Long?): TeamAccess {
        val members = client.preparedQuery(
            "SELECT account_id, built_in_role, source_occurred_at, authorization_version " +
                "FROM tb_team_member_projections " +
                "WHERE team_id = \$1 AND NOT deleted " +
                "AND NOT EXISTS (SELECT 1 FROM tb_team_role_domain_tombstones WHERE team_id = \$1) " +
                "AND (\$2::bigint IS NULL OR account_id = \$2)",
        ).execute(Tuple.of(teamId, accountId)).coAwait().associateBy { it.getLong("account_id") }
        val teamRoles = client.preparedQuery(
            "SELECT d.permissions, d.is_default, a.account_id FROM tb_team_role_definitions d " +
                "LEFT JOIN tb_account_team_roles a ON a.role_id = d.id AND a.team_id = d.team_id " +
                "AND (\$2::bigint IS NULL OR a.account_id = \$2) " +
                "WHERE d.team_id = \$1 AND (d.is_default OR a.account_id IS NOT NULL)",
        ).execute(Tuple.of(teamId, accountId)).coAwait().toList()
        return TeamAccess(
            members,
            teamRoles.filter { it.getBoolean("is_default") }.map { it.getJsonObject("permissions") },
            teamRoles.filter { !it.getBoolean("is_default") && it.getLong("account_id") != null }
                .groupBy({ it.getLong("account_id") }, { it.getJsonObject("permissions") }),
        )
    }

    suspend fun removeTeamAssignments(client: SqlClient, teamId: Long, accountId: Long?, cutoff: Instant? = null) {
        client.preparedQuery(
            """
            DELETE FROM account_project_role a USING tb_project_lifecycle_projections p
            WHERE p.project_id = a.project_id AND p.team_id = ${'$'}1
              AND (${'$'}2::bigint IS NULL OR a.account_id = ${'$'}2)
              AND (${'$'}3::timestamptz IS NULL OR a.source_membership_version <= ${'$'}3)
            """.trimIndent(),
        ).execute(Tuple.of(teamId, accountId, cutoff?.atOffset(ZoneOffset.UTC))).coAwait()
        refreshTeam(client, teamId, accountId)
    }

    suspend fun publishSnapshot(client: SqlClient) {
        val projects = client.query("SELECT project_id FROM tb_project_lifecycle_projections ORDER BY project_id").execute().coAwait()
        projects.forEach { refresh(client, it.getLong("project_id")) }
        val states = client.query("SELECT * FROM tb_project_access_states ORDER BY project_id, account_id").execute().coAwait()
        states.forEach { outbox.enqueue(client, event(it)) }
    }

    private fun Row.permissions() = ProjectPermissions(
        getBoolean("can_read"), getBoolean("can_write"), getBoolean("can_manage"), getBoolean("can_reorder"),
    )

    private data class TeamAccess(
        val members: Map<Long, Row>,
        val defaults: List<JsonObject>,
        val assigned: Map<Long, List<JsonObject>>,
    )

    private fun event(row: Row): PreferenceEvent {
        val projectId = row.getLong("project_id")
        val accountId = row.getLong("account_id")
        val version = row.getOffsetDateTime("state_occurred_at").toInstant()
        val authorizationVersion = row.getOffsetDateTime("source_authorization_version")?.toInstant()
        return PreferenceEvent(
            PreferenceEvents.PROJECT_ACCESS_STATE_TOPIC,
            "$projectId:$accountId",
            JsonObject().put("eventType", "UPSERT")
                .put("projectId", projectId).put("accountId", accountId)
                .put("read", row.getBoolean("can_read")).put("write", row.getBoolean("can_write"))
                .put("manage", row.getBoolean("can_manage")).put("reorder", row.getBoolean("can_reorder"))
                .put("sourceMembershipVersion", row.getOffsetDateTime("source_membership_version").toInstant().toString())
                .put("sourceAuthorizationVersion", authorizationVersion?.toString())
                .put("occurredAt", version.toString()),
            version,
        )
    }
}
