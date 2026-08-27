package com.cowork.preference.repository

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.time.ZoneOffset

class TeamRoleRepository(private val pool: Pool) {

    internal suspend fun isTeamDeleted(client: SqlClient, teamId: Long): Boolean {
        val rows = client.preparedQuery(
            "SELECT EXISTS (SELECT 1 FROM tb_team_role_domain_tombstones WHERE team_id = \$1) AS deleted",
        ).execute(Tuple.of(teamId)).coAwait()
        return rows.first().getBoolean("deleted")
    }

    internal suspend fun markTeamDeleted(client: SqlClient, teamId: Long, occurredAt: Instant) {
        client.preparedQuery(
            """
            INSERT INTO tb_team_role_domain_tombstones (team_id, source_occurred_at)
            VALUES (${'$'}1, ${'$'}2)
            ON CONFLICT (team_id)
            DO UPDATE SET
                source_occurred_at = GREATEST(
                    tb_team_role_domain_tombstones.source_occurred_at,
                    EXCLUDED.source_occurred_at
                ),
                updated_at = now()
            """.trimIndent(),
        ).execute(Tuple.of(teamId, occurredAt.atOffset(ZoneOffset.UTC))).coAwait()
    }

    internal suspend fun findRoles(client: SqlClient, teamId: Long): List<TeamRoleDefinition> {
        val rows = client.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1
            ORDER BY priority DESC, id ASC
            """,
        ).execute(Tuple.of(teamId)).coAwait()
        return rows.map { it.toTeamRoleDefinition() }
    }

    suspend fun findRole(teamId: Long, roleId: Long): TeamRoleDefinition? = findRole(pool, teamId, roleId)

    internal suspend fun findRole(client: SqlClient, teamId: Long, roleId: Long): TeamRoleDefinition? {
        val rows = client.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1 AND id = ${'$'}2
            """,
        ).execute(Tuple.of(teamId, roleId)).coAwait()
        return rows.firstOrNull()?.toTeamRoleDefinition()
    }

    suspend fun findRoleByName(teamId: Long, name: String): TeamRoleDefinition? = findRoleByName(pool, teamId, name)

    internal suspend fun findRoleByName(client: SqlClient, teamId: Long, name: String): TeamRoleDefinition? {
        val rows = client.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1 AND name = ${'$'}2
            """,
        ).execute(Tuple.of(teamId, name)).coAwait()
        return rows.firstOrNull()?.toTeamRoleDefinition()
    }

    suspend fun findRoleByNameExceptId(teamId: Long, name: String, roleId: Long): TeamRoleDefinition? =
        findRoleByNameExceptId(pool, teamId, name, roleId)

    internal suspend fun findRoleByNameExceptId(
        client: SqlClient,
        teamId: Long,
        name: String,
        roleId: Long,
    ): TeamRoleDefinition? {
        val rows = client.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1 AND name = ${'$'}2 AND id <> ${'$'}3
            """,
        ).execute(Tuple.of(teamId, name, roleId)).coAwait()
        return rows.firstOrNull()?.toTeamRoleDefinition()
    }

    internal suspend fun insertRole(
        client: SqlClient,
        teamId: Long,
        name: String,
        colorHex: String,
        priority: Int,
        mentionable: Boolean,
        permissions: Set<String>,
    ): TeamRoleDefinition {
        val rows = client.preparedQuery(
            """
            INSERT INTO tb_team_role_definitions (team_id, name, color_hex, priority, mentionable, permissions)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6::jsonb)
            RETURNING id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            """,
        ).execute(Tuple.of(teamId, name, colorHex, priority, mentionable, JsonArray(permissions.toList()))).coAwait()
        return rows.first().toTeamRoleDefinition()
    }

    internal suspend fun updateRole(
        client: SqlClient,
        roleId: Long,
        name: String,
        colorHex: String,
        priority: Int,
        mentionable: Boolean,
        permissions: Set<String>,
    ): TeamRoleDefinition {
        val rows = client.preparedQuery(
            """
            UPDATE tb_team_role_definitions
            SET name = ${'$'}1, color_hex = ${'$'}2, priority = ${'$'}3, mentionable = ${'$'}4, permissions = ${'$'}5::jsonb
            WHERE id = ${'$'}6
            RETURNING id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            """,
        ).execute(Tuple.of(name, colorHex, priority, mentionable, JsonArray(permissions.toList()), roleId)).coAwait()
        return rows.first().toTeamRoleDefinition()
    }

    internal suspend fun deleteRole(client: SqlClient, teamId: Long, roleId: Long) {
        client.preparedQuery("DELETE FROM tb_account_team_roles WHERE team_id = \$1 AND role_id = \$2")
            .execute(Tuple.of(teamId, roleId))
            .coAwait()
        client.preparedQuery("DELETE FROM tb_team_role_definitions WHERE team_id = \$1 AND id = \$2")
            .execute(Tuple.of(teamId, roleId))
            .coAwait()
    }

    internal suspend fun findMemberRoles(client: SqlClient, teamId: Long): List<AccountTeamRole> {
        val rows = client.preparedQuery(
            """
            SELECT account_id, team_id, role_id, source_membership_version, updated_at
            FROM tb_account_team_roles
            WHERE team_id = ${'$'}1
            """.trimIndent(),
        ).execute(Tuple.of(teamId)).coAwait()
        return rows.map { it.toAccountTeamRole() }
    }

    suspend fun findAssignment(accountId: Long, teamId: Long, roleId: Long): AccountTeamRole? =
        findAssignment(pool, accountId, teamId, roleId)

    internal suspend fun findAssignment(
        client: SqlClient,
        accountId: Long,
        teamId: Long,
        roleId: Long,
    ): AccountTeamRole? {
        val rows = client.preparedQuery(
            """
            SELECT account_id, team_id, role_id, source_membership_version, updated_at
            FROM tb_account_team_roles
            WHERE account_id = ${'$'}1 AND team_id = ${'$'}2 AND role_id = ${'$'}3
            """.trimIndent(),
        ).execute(Tuple.of(accountId, teamId, roleId)).coAwait()
        return rows.firstOrNull()?.toAccountTeamRole()
    }

    internal suspend fun findRolePage(client: SqlClient, afterRoleId: Long, limit: Int): List<TeamRoleDefinition> {
        val rows = client.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE id > ${'$'}1
            ORDER BY id
            LIMIT ${'$'}2
            FOR SHARE
            """.trimIndent(),
        ).execute(Tuple.of(afterRoleId, limit)).coAwait()
        return rows.map { it.toTeamRoleDefinition() }
    }

    internal suspend fun findAssignmentPage(
        client: SqlClient,
        afterTeamId: Long,
        afterAccountId: Long,
        afterRoleId: Long,
        limit: Int,
    ): List<AccountTeamRole> {
        val rows = client.preparedQuery(
            """
            SELECT account_id, team_id, role_id, source_membership_version, updated_at
            FROM tb_account_team_roles
            WHERE (team_id, account_id, role_id) > (${'$'}1, ${'$'}2, ${'$'}3)
            ORDER BY team_id, account_id, role_id
            LIMIT ${'$'}4
            FOR SHARE
            """.trimIndent(),
        ).execute(Tuple.of(afterTeamId, afterAccountId, afterRoleId, limit)).coAwait()
        return rows.map { it.toAccountTeamRole() }
    }

    internal suspend fun assignRole(
        client: SqlClient,
        accountId: Long,
        teamId: Long,
        roleId: Long,
        sourceMembershipVersion: Instant,
    ): TeamRoleDefinition {
        client.preparedQuery(
            """
            INSERT INTO tb_account_team_roles (account_id, team_id, role_id, source_membership_version)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4)
            ON CONFLICT (account_id, team_id, role_id)
            DO UPDATE SET
                source_membership_version = EXCLUDED.source_membership_version,
                updated_at = GREATEST(
                    clock_timestamp(),
                    tb_account_team_roles.updated_at + INTERVAL '1 microsecond'
                )
            WHERE EXCLUDED.source_membership_version > tb_account_team_roles.source_membership_version
            """,
        ).execute(
            Tuple.of(accountId, teamId, roleId, sourceMembershipVersion.atOffset(ZoneOffset.UTC)),
        ).coAwait()

        return requireNotNull(findRole(client, teamId, roleId))
    }

    internal suspend fun removeRole(client: SqlClient, accountId: Long, teamId: Long, roleId: Long) {
        client.preparedQuery(
            "DELETE FROM tb_account_team_roles WHERE account_id = \$1 AND team_id = \$2 AND role_id = \$3",
        ).execute(Tuple.of(accountId, teamId, roleId)).coAwait()
    }

    internal suspend fun removeMemberRolesAtOrBefore(
        client: SqlClient,
        accountId: Long,
        teamId: Long,
        membershipVersion: Instant,
    ): List<AccountTeamRole> {
        val rows = client.preparedQuery(
            """
            DELETE FROM tb_account_team_roles
            WHERE account_id = ${'$'}1
              AND team_id = ${'$'}2
              AND (source_membership_version IS NULL OR source_membership_version <= ${'$'}3)
            RETURNING account_id, team_id, role_id, source_membership_version, updated_at
            """.trimIndent(),
        ).execute(Tuple.of(accountId, teamId, membershipVersion.atOffset(ZoneOffset.UTC))).coAwait()
        return rows.map { it.toAccountTeamRole() }
    }

    internal suspend fun deleteTeamRoles(client: SqlClient, teamId: Long) {
        client.preparedQuery("DELETE FROM tb_account_team_roles WHERE team_id = \$1")
            .execute(Tuple.of(teamId))
            .coAwait()
        client.preparedQuery("DELETE FROM tb_team_role_definitions WHERE team_id = \$1")
            .execute(Tuple.of(teamId))
            .coAwait()
    }

    private fun Row.toTeamRoleDefinition(): TeamRoleDefinition = TeamRoleDefinition(
        id = getLong("id"),
        teamId = getLong("team_id"),
        name = getString("name"),
        colorHex = getString("color_hex"),
        priority = getInteger("priority"),
        mentionable = getBoolean("mentionable"),
        permissions = getJsonArray("permissions")?.map { it.toString() }?.toSet() ?: emptySet(),
        createdAt = getOffsetDateTime("created_at"),
        updatedAt = getOffsetDateTime("updated_at"),
    )

    private fun Row.toAccountTeamRole(): AccountTeamRole = AccountTeamRole(
        accountId = getLong("account_id"),
        teamId = getLong("team_id"),
        roleId = getLong("role_id"),
        sourceMembershipVersion = getOffsetDateTime("source_membership_version").toInstant(),
        updatedAt = getOffsetDateTime("updated_at"),
    )
}
