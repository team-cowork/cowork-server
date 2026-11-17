package com.cowork.team.global.policy

import com.cowork.team.global.consumer.AccountRolePolicyEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.sql.Timestamp

@Repository
class AccountPolicyProjection(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun apply(key: String, event: AccountRolePolicyEvent) {
        jdbc.update(
            """
            INSERT INTO tb_account_policy_projections
                (policy_key, record_type, role_id, account_id, is_default, permissions_json, deleted, source_occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                is_default = IF(VALUES(source_occurred_at) > source_occurred_at, VALUES(is_default), is_default),
                permissions_json = IF(VALUES(source_occurred_at) > source_occurred_at, VALUES(permissions_json), permissions_json),
                deleted = IF(VALUES(source_occurred_at) > source_occurred_at, VALUES(deleted),
                    IF(VALUES(source_occurred_at) = source_occurred_at, deleted OR VALUES(deleted), deleted)),
                source_occurred_at = GREATEST(source_occurred_at, VALUES(source_occurred_at))
            """.trimIndent(),
            key, event.eventType, event.roleId, event.accountId, event.isDefault == true,
            mapper.writeValueAsString(event.permissions.orEmpty()), event.deleted, Timestamp.from(event.occurredAt),
        )
    }

    fun findAccountPolicies(accountId: Long): List<Map<String, Boolean>> = jdbc.query(
        """
            SELECT r.permissions_json FROM tb_account_policy_projections r
            WHERE r.record_type = 'ROLE' AND NOT r.deleted AND (r.is_default OR EXISTS (
                SELECT 1 FROM tb_account_policy_projections a
                WHERE a.record_type = 'ASSIGNMENT' AND a.account_id = ? AND a.role_id = r.role_id AND NOT a.deleted
            ))
        """.trimIndent(),
        { row, _ -> mapper.readValue<Map<String, Boolean>>(row.getString("permissions_json")) },
        accountId,
    )

    fun findTeamPolicies(accountId: Long): List<Map<String, Boolean>> = jdbc.query(
        """
            SELECT r.permissions_json FROM tb_team_role_projections r
            JOIN tb_team_members m ON m.team_id = r.team_id AND m.user_id = ?
            WHERE NOT r.deleted AND (r.is_default OR EXISTS (
                SELECT 1 FROM tb_team_role_assignment_projections a
                WHERE a.team_id = r.team_id AND a.role_id = r.role_id AND a.account_id = m.user_id AND NOT a.deleted
                  AND NOT EXISTS (
                      SELECT 1 FROM tb_team_role_member_tombstones f
                      WHERE f.team_id = a.team_id AND f.account_id = a.account_id
                        AND f.source_occurred_at >= a.source_occurred_at
                  )
            ))
        """.trimIndent(),
        { row, _ -> mapper.readValue<Map<String, Boolean>>(row.getString("permissions_json")) },
        accountId,
    )

    companion object {
        const val TOPIC = "preference.account-role.state.v1"
        const val GROUP = "cowork-team.account-role-projection.v1"
    }
}
