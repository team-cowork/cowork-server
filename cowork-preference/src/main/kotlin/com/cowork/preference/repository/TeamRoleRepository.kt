package com.cowork.preference.repository

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleAssignmentState
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.domain.TeamRoleMemberFence
import com.cowork.preference.domain.TeamRoleState
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

    internal suspend fun findRoleStatePage(
        client: SqlClient,
        afterTeamId: Long,
        afterRoleId: Long,
        limit: Int,
    ): List<TeamRoleState> {
        val rows = client.preparedQuery(
            """
            WITH active_states AS (
                SELECT
                    team_id,
                    id AS role_id,
                    name,
                    color_hex,
                    priority,
                    mentionable,
                    permissions,
                    created_at,
                    updated_at,
                    COALESCE(updated_at, created_at) AS state_occurred_at,
                    FALSE AS deleted
                FROM tb_team_role_definitions
                WHERE (team_id, id) > (${'$'}1, ${'$'}2)
                ORDER BY team_id, id
                LIMIT ${'$'}3
                FOR SHARE
            ), deleted_states AS (
                SELECT
                    team_id,
                    role_id,
                    NULL::VARCHAR(50) AS name,
                    NULL::VARCHAR(7) AS color_hex,
                    NULL::INTEGER AS priority,
                    NULL::BOOLEAN AS mentionable,
                    NULL::JSONB AS permissions,
                    NULL::TIMESTAMPTZ AS created_at,
                    NULL::TIMESTAMPTZ AS updated_at,
                    state_occurred_at,
                    TRUE AS deleted
                FROM tb_team_role_tombstones
                WHERE (team_id, role_id) > (${'$'}1, ${'$'}2)
                ORDER BY team_id, role_id
                LIMIT ${'$'}3
                FOR SHARE
            )
            SELECT * FROM active_states
            UNION ALL
            SELECT * FROM deleted_states
            ORDER BY team_id, role_id
            LIMIT ${'$'}3
            """.trimIndent(),
        ).execute(Tuple.of(afterTeamId, afterRoleId, limit)).coAwait()
        return rows.map { it.toTeamRoleState() }
    }

    internal suspend fun findAssignmentStatePage(
        client: SqlClient,
        afterTeamId: Long,
        afterAccountId: Long,
        afterRoleId: Long,
        limit: Int,
    ): List<TeamRoleAssignmentState> {
        val rows = client.preparedQuery(
            """
            WITH active_states AS (
                SELECT
                    team_id,
                    account_id,
                    role_id,
                    source_membership_version,
                    updated_at AS state_occurred_at,
                    FALSE AS deleted
                FROM tb_account_team_roles
                WHERE (team_id, account_id, role_id) > (${'$'}1, ${'$'}2, ${'$'}3)
                ORDER BY team_id, account_id, role_id
                LIMIT ${'$'}4
                FOR SHARE
            ), deleted_states AS (
                SELECT
                    team_id,
                    account_id,
                    role_id,
                    NULL::TIMESTAMPTZ AS source_membership_version,
                    state_occurred_at,
                    TRUE AS deleted
                FROM tb_team_role_assignment_tombstones
                WHERE (team_id, account_id, role_id) > (${'$'}1, ${'$'}2, ${'$'}3)
                ORDER BY team_id, account_id, role_id
                LIMIT ${'$'}4
                FOR SHARE
            )
            SELECT * FROM active_states
            UNION ALL
            SELECT * FROM deleted_states
            ORDER BY team_id, account_id, role_id
            LIMIT ${'$'}4
            """.trimIndent(),
        ).execute(Tuple.of(afterTeamId, afterAccountId, afterRoleId, limit)).coAwait()
        return rows.map { it.toTeamRoleAssignmentState() }
    }

    internal suspend fun findMemberFencePage(
        client: SqlClient,
        afterTeamId: Long,
        afterAccountId: Long,
        limit: Int,
    ): List<TeamRoleMemberFence> {
        val rows = client.preparedQuery(
            """
            SELECT team_id, account_id, state_occurred_at
            FROM tb_team_role_member_fences
            WHERE (team_id, account_id) > (${'$'}1, ${'$'}2)
            ORDER BY team_id, account_id
            LIMIT ${'$'}3
            FOR SHARE
            """.trimIndent(),
        ).execute(Tuple.of(afterTeamId, afterAccountId, limit)).coAwait()
        return rows.map { it.toTeamRoleMemberFence() }
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
            WITH removed_tombstone AS (
                DELETE FROM tb_team_role_assignment_tombstones
                WHERE team_id = ${'$'}2 AND account_id = ${'$'}1 AND role_id = ${'$'}3
                RETURNING state_occurred_at
            ), prior_versions AS (
                SELECT state_occurred_at FROM removed_tombstone
                UNION ALL
                SELECT state_occurred_at
                FROM tb_team_role_member_fences
                WHERE team_id = ${'$'}2 AND account_id = ${'$'}1
                UNION ALL
                SELECT updated_at AS state_occurred_at
                FROM tb_account_team_roles
                WHERE team_id = ${'$'}2 AND account_id = ${'$'}1 AND role_id = ${'$'}3
            ), next_state AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    COALESCE(
                        MAX(state_occurred_at) + INTERVAL '1 microsecond',
                        '-infinity'::TIMESTAMPTZ
                    )
                ) AS state_occurred_at
                FROM prior_versions
            )
            INSERT INTO tb_account_team_roles (
                account_id,
                team_id,
                role_id,
                source_membership_version,
                updated_at
            )
            SELECT ${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, state_occurred_at
            FROM next_state
            ON CONFLICT (account_id, team_id, role_id)
            DO UPDATE SET
                source_membership_version = EXCLUDED.source_membership_version,
                updated_at = EXCLUDED.updated_at
            WHERE EXCLUDED.source_membership_version > tb_account_team_roles.source_membership_version
            """,
        ).execute(
            Tuple.of(accountId, teamId, roleId, sourceMembershipVersion.atOffset(ZoneOffset.UTC)),
        ).coAwait()

        return requireNotNull(findRole(client, teamId, roleId))
    }

    internal suspend fun deleteAssignment(
        client: SqlClient,
        accountId: Long,
        teamId: Long,
        roleId: Long,
        minimumOccurredAt: Instant,
    ): TeamRoleAssignmentState {
        val rows = client.preparedQuery(
            """
            WITH removed_assignment AS (
                DELETE FROM tb_account_team_roles
                WHERE account_id = ${'$'}1 AND team_id = ${'$'}2 AND role_id = ${'$'}3
                RETURNING updated_at
            ), prior_versions AS (
                SELECT updated_at AS state_occurred_at FROM removed_assignment
                UNION ALL
                SELECT state_occurred_at
                FROM tb_team_role_assignment_tombstones
                WHERE team_id = ${'$'}2 AND account_id = ${'$'}1 AND role_id = ${'$'}3
                UNION ALL
                SELECT state_occurred_at
                FROM tb_team_role_member_fences
                WHERE team_id = ${'$'}2 AND account_id = ${'$'}1
                UNION ALL
                SELECT ${'$'}4::TIMESTAMPTZ
            ), next_state AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    MAX(state_occurred_at) + INTERVAL '1 microsecond'
                ) AS state_occurred_at
                FROM prior_versions
            ), persisted AS (
                INSERT INTO tb_team_role_assignment_tombstones (
                    team_id,
                    account_id,
                    role_id,
                    state_occurred_at
                )
                SELECT ${'$'}2, ${'$'}1, ${'$'}3, state_occurred_at
                FROM next_state
                ON CONFLICT (team_id, account_id, role_id)
                DO UPDATE SET
                    state_occurred_at = GREATEST(
                        clock_timestamp(),
                        EXCLUDED.state_occurred_at,
                        tb_team_role_assignment_tombstones.state_occurred_at + INTERVAL '1 microsecond'
                    ),
                    updated_at = clock_timestamp()
                RETURNING state_occurred_at
            )
            SELECT state_occurred_at FROM persisted
            """.trimIndent(),
        ).execute(
            Tuple.of(accountId, teamId, roleId, minimumOccurredAt.atOffset(ZoneOffset.UTC)),
        ).coAwait()
        return TeamRoleAssignmentState(
            teamId = teamId,
            accountId = accountId,
            roleId = roleId,
            assignment = null,
            stateOccurredAt = rows.first().getOffsetDateTime("state_occurred_at").toInstant(),
        )
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

    internal suspend fun upsertMemberFence(
        client: SqlClient,
        teamId: Long,
        accountId: Long,
        minimumOccurredAt: Instant,
    ): TeamRoleMemberFence {
        val rows = client.preparedQuery(
            """
            WITH next_state AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    ${'$'}3::TIMESTAMPTZ,
                    COALESCE(
                        (
                            SELECT state_occurred_at + INTERVAL '1 microsecond'
                            FROM tb_team_role_member_fences
                            WHERE team_id = ${'$'}1 AND account_id = ${'$'}2
                        ),
                        '-infinity'::TIMESTAMPTZ
                    )
                ) AS state_occurred_at
            ), persisted AS (
                INSERT INTO tb_team_role_member_fences (team_id, account_id, state_occurred_at)
                SELECT ${'$'}1, ${'$'}2, state_occurred_at
                FROM next_state
                ON CONFLICT (team_id, account_id)
                DO UPDATE SET
                    state_occurred_at = GREATEST(
                        clock_timestamp(),
                        EXCLUDED.state_occurred_at,
                        tb_team_role_member_fences.state_occurred_at + INTERVAL '1 microsecond'
                    ),
                    updated_at = clock_timestamp()
                RETURNING team_id, account_id, state_occurred_at
            )
            SELECT team_id, account_id, state_occurred_at FROM persisted
            """.trimIndent(),
        ).execute(Tuple.of(teamId, accountId, minimumOccurredAt.atOffset(ZoneOffset.UTC))).coAwait()
        return rows.first().toTeamRoleMemberFence()
    }

    internal suspend fun deleteRoleWithTombstone(
        client: SqlClient,
        teamId: Long,
        roleId: Long,
        minimumOccurredAt: Instant,
    ): TeamRoleState {
        val rows = client.preparedQuery(
            """
            WITH removed_role AS (
                DELETE FROM tb_team_role_definitions
                WHERE team_id = ${'$'}1 AND id = ${'$'}2
                RETURNING COALESCE(updated_at, created_at) AS state_occurred_at
            ), prior_versions AS (
                SELECT state_occurred_at FROM removed_role
                UNION ALL
                SELECT state_occurred_at
                FROM tb_team_role_tombstones
                WHERE team_id = ${'$'}1 AND role_id = ${'$'}2
                UNION ALL
                SELECT ${'$'}3::TIMESTAMPTZ
            ), next_state AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    MAX(state_occurred_at) + INTERVAL '1 microsecond'
                ) AS state_occurred_at
                FROM prior_versions
            ), persisted AS (
                INSERT INTO tb_team_role_tombstones (team_id, role_id, state_occurred_at)
                SELECT ${'$'}1, ${'$'}2, state_occurred_at
                FROM next_state
                ON CONFLICT (team_id, role_id)
                DO UPDATE SET
                    state_occurred_at = GREATEST(
                        clock_timestamp(),
                        EXCLUDED.state_occurred_at,
                        tb_team_role_tombstones.state_occurred_at + INTERVAL '1 microsecond'
                    ),
                    updated_at = clock_timestamp()
                RETURNING state_occurred_at
            )
            SELECT state_occurred_at FROM persisted
            """.trimIndent(),
        ).execute(Tuple.of(teamId, roleId, minimumOccurredAt.atOffset(ZoneOffset.UTC))).coAwait()
        return TeamRoleState(
            teamId = teamId,
            roleId = roleId,
            role = null,
            stateOccurredAt = rows.first().getOffsetDateTime("state_occurred_at").toInstant(),
        )
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

    private fun Row.toTeamRoleState(): TeamRoleState {
        val deleted = getBoolean("deleted")
        return TeamRoleState(
            teamId = getLong("team_id"),
            roleId = getLong("role_id"),
            role = if (deleted) null else toTeamRoleDefinitionFromState(),
            stateOccurredAt = getOffsetDateTime("state_occurred_at").toInstant(),
        )
    }

    private fun Row.toTeamRoleDefinitionFromState(): TeamRoleDefinition = TeamRoleDefinition(
        id = getLong("role_id"),
        teamId = getLong("team_id"),
        name = getString("name"),
        colorHex = getString("color_hex"),
        priority = getInteger("priority"),
        mentionable = getBoolean("mentionable"),
        permissions = getJsonArray("permissions")?.map { it.toString() }?.toSet() ?: emptySet(),
        createdAt = getOffsetDateTime("created_at"),
        updatedAt = getOffsetDateTime("updated_at"),
    )

    private fun Row.toTeamRoleAssignmentState(): TeamRoleAssignmentState {
        val deleted = getBoolean("deleted")
        val stateOccurredAt = getOffsetDateTime("state_occurred_at")
        return TeamRoleAssignmentState(
            teamId = getLong("team_id"),
            accountId = getLong("account_id"),
            roleId = getLong("role_id"),
            assignment = if (deleted) {
                null
            } else {
                AccountTeamRole(
                    accountId = getLong("account_id"),
                    teamId = getLong("team_id"),
                    roleId = getLong("role_id"),
                    sourceMembershipVersion = getOffsetDateTime("source_membership_version").toInstant(),
                    updatedAt = stateOccurredAt,
                )
            },
            stateOccurredAt = stateOccurredAt.toInstant(),
        )
    }

    private fun Row.toTeamRoleMemberFence(): TeamRoleMemberFence = TeamRoleMemberFence(
        teamId = getLong("team_id"),
        accountId = getLong("account_id"),
        stateOccurredAt = getOffsetDateTime("state_occurred_at").toInstant(),
    )
}
