package com.cowork.preference.domain.teamrole.repository

import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.teamrole.model.TeamRolePermissions
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 팀 권한과 채널 권한 판정 결과가 함께 쓰는 팀 멤버와 역할 입력. */
internal class TeamAccessInputs private constructor(
    private val members: Map<Long, Row>,
    private val defaults: List<PolicyRole>,
    private val assigned: Map<Long, List<PolicyRole>>,
) {
    val memberIds: Set<Long> get() = members.keys

    fun isActive(accountId: Long): Boolean = accountId in members

    fun roles(accountId: Long): List<PolicyRole> = defaults + assigned[accountId].orEmpty()

    /** 활성 멤버가 아니면 1970-01-01이다. */
    fun membershipVersion(accountId: Long): OffsetDateTime =
        members[accountId]?.getOffsetDateTime("source_occurred_at") ?: Instant.EPOCH.atOffset(ZoneOffset.UTC)

    fun authorizationVersion(accountId: Long): OffsetDateTime? =
        members[accountId]?.getOffsetDateTime("authorization_version")

    companion object {
        /** [accountId]가 null이면 팀의 모든 멤버를 읽는다. 삭제된 팀에는 활성 멤버가 없다. */
        suspend fun load(client: SqlClient, teamId: Long, accountId: Long?): TeamAccessInputs {
            val members = client.preparedQuery(
                "SELECT account_id, source_occurred_at, authorization_version FROM tb_team_member_projections " +
                    "WHERE team_id = $1 AND NOT deleted " +
                    "AND NOT EXISTS (SELECT 1 FROM tb_team_role_domain_tombstones WHERE team_id = $1) " +
                    "AND ($2::bigint IS NULL OR account_id = $2)",
            ).execute(Tuple.of(teamId, accountId)).coAwait().associateBy { it.getLong("account_id") }
            val roles = client.preparedQuery(
                "SELECT d.id, d.name, d.permissions, d.is_default, a.account_id FROM tb_team_role_definitions d " +
                    "LEFT JOIN tb_account_team_roles a ON a.role_id = d.id AND a.team_id = d.team_id " +
                    "AND ($2::bigint IS NULL OR a.account_id = $2) " +
                    "WHERE d.team_id = $1 AND (d.is_default OR a.account_id IS NOT NULL)",
            ).execute(Tuple.of(teamId, accountId)).coAwait().toList()
            return TeamAccessInputs(
                members,
                roles.filter { it.getBoolean("is_default") }.map { it.policyRole() }.distinct(),
                roles.filter { !it.getBoolean("is_default") && it.getLong("account_id") != null }
                    .groupBy({ it.getLong("account_id") }, { it.policyRole() }),
            )
        }

        private fun Row.policyRole() = PolicyRole(
            getLong("id"),
            getString("name"),
            TeamRolePermissions.fromStored(getJsonObject("permissions")),
            getBoolean("is_default"),
        )
    }
}
