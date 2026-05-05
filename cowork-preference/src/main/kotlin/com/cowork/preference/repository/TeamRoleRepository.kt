package com.cowork.preference.repository

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple

class TeamRoleRepository(private val pool: Pool) {

    suspend fun findRoles(teamId: Long): List<TeamRoleDefinition> {
        val rows = pool.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1
            ORDER BY priority DESC, id ASC
            """
        ).execute(Tuple.of(teamId)).coAwait()
        return rows.map { it.toTeamRoleDefinition() }
    }

    suspend fun findRole(teamId: Long, roleId: Long): TeamRoleDefinition? {
        val rows = pool.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1 AND id = ${'$'}2
            """
        ).execute(Tuple.of(teamId, roleId)).coAwait()
        return rows.firstOrNull()?.toTeamRoleDefinition()
    }

    suspend fun findRoleByName(teamId: Long, name: String): TeamRoleDefinition? {
        val rows = pool.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1 AND name = ${'$'}2
            """
        ).execute(Tuple.of(teamId, name)).coAwait()
        return rows.firstOrNull()?.toTeamRoleDefinition()
    }

    suspend fun findRoleByNameExceptId(teamId: Long, name: String, roleId: Long): TeamRoleDefinition? {
        val rows = pool.preparedQuery(
            """
            SELECT id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            FROM tb_team_role_definitions
            WHERE team_id = ${'$'}1 AND name = ${'$'}2 AND id <> ${'$'}3
            """
        ).execute(Tuple.of(teamId, name, roleId)).coAwait()
        return rows.firstOrNull()?.toTeamRoleDefinition()
    }

    suspend fun insertRole(
        teamId: Long,
        name: String,
        colorHex: String,
        priority: Int,
        mentionable: Boolean,
        permissions: Set<String>,
    ): TeamRoleDefinition {
        val rows = pool.preparedQuery(
            """
            INSERT INTO tb_team_role_definitions (team_id, name, color_hex, priority, mentionable, permissions)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6::jsonb)
            RETURNING id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            """
        ).execute(Tuple.of(teamId, name, colorHex, priority, mentionable, JsonArray(permissions.toList()))).coAwait()
        return rows.first().toTeamRoleDefinition()
    }

    suspend fun updateRole(
        roleId: Long,
        name: String,
        colorHex: String,
        priority: Int,
        mentionable: Boolean,
        permissions: Set<String>,
    ): TeamRoleDefinition {
        val rows = pool.preparedQuery(
            """
            UPDATE tb_team_role_definitions
            SET name = ${'$'}1, color_hex = ${'$'}2, priority = ${'$'}3, mentionable = ${'$'}4, permissions = ${'$'}5::jsonb
            WHERE id = ${'$'}6
            RETURNING id, team_id, name, color_hex, priority, mentionable, permissions, created_at, updated_at
            """
        ).execute(Tuple.of(name, colorHex, priority, mentionable, JsonArray(permissions.toList()), roleId)).coAwait()
        return rows.first().toTeamRoleDefinition()
    }

    suspend fun deleteRole(teamId: Long, roleId: Long) {
        pool.withTransaction { client ->
            client.preparedQuery("DELETE FROM tb_account_team_roles WHERE team_id = \$1 AND role_id = \$2")
                .execute(Tuple.of(teamId, roleId))
                .compose {
                    client.preparedQuery("DELETE FROM tb_team_role_definitions WHERE team_id = \$1 AND id = \$2")
                        .execute(Tuple.of(teamId, roleId))
                }
        }.coAwait()
    }

    suspend fun findMemberRoles(teamId: Long): List<AccountTeamRole> {
        val rows = pool.preparedQuery(
            "SELECT account_id, team_id, role_id FROM tb_account_team_roles WHERE team_id = \$1"
        ).execute(Tuple.of(teamId)).coAwait()
        return rows.map {
            AccountTeamRole(
                accountId = it.getLong("account_id"),
                teamId = it.getLong("team_id"),
                roleId = it.getLong("role_id"),
            )
        }
    }

    suspend fun findMemberRoleDefinitions(teamId: Long, accountId: Long): List<TeamRoleDefinition> {
        val rows = pool.preparedQuery(
            """
            SELECT r.id, r.team_id, r.name, r.color_hex, r.priority, r.mentionable, r.permissions, r.created_at, r.updated_at
            FROM tb_team_role_definitions r
            JOIN tb_account_team_roles ar ON ar.role_id = r.id
            WHERE ar.team_id = ${'$'}1 AND ar.account_id = ${'$'}2
            ORDER BY r.priority DESC, r.id ASC
            """
        ).execute(Tuple.of(teamId, accountId)).coAwait()
        return rows.map { it.toTeamRoleDefinition() }
    }

    suspend fun assignRole(accountId: Long, teamId: Long, roleId: Long): TeamRoleDefinition {
        pool.preparedQuery(
            """
            INSERT INTO tb_account_team_roles (account_id, team_id, role_id)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3)
            ON CONFLICT DO NOTHING
            """
        ).execute(Tuple.of(accountId, teamId, roleId)).coAwait()

        return requireNotNull(findRole(teamId, roleId))
    }

    suspend fun removeRole(accountId: Long, teamId: Long, roleId: Long) {
        pool.preparedQuery(
            "DELETE FROM tb_account_team_roles WHERE account_id = \$1 AND team_id = \$2 AND role_id = \$3"
        ).execute(Tuple.of(accountId, teamId, roleId)).coAwait()
    }

    suspend fun removeMemberRoles(accountId: Long, teamId: Long) {
        pool.preparedQuery(
            "DELETE FROM tb_account_team_roles WHERE account_id = \$1 AND team_id = \$2"
        ).execute(Tuple.of(accountId, teamId)).coAwait()
    }

    private fun Row.toTeamRoleDefinition(): TeamRoleDefinition =
        TeamRoleDefinition(
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
}
