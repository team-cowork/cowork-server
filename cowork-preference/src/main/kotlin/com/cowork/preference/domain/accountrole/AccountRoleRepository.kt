package com.cowork.preference.domain.accountrole

import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceEventPayload.basePayload
import com.cowork.preference.global.messaging.event.PreferenceTopics
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class AccountRoleRepository(private val outbox: PreferenceOutboxRepository) {
    suspend fun assignments(client: SqlClient, accountId: Long): List<Long> = client.preparedQuery(
        "SELECT r.id FROM tb_account_role_definitions r WHERE NOT r.deleted AND NOT r.is_default AND EXISTS (" +
            "SELECT 1 FROM tb_account_role_assignments a WHERE a.role_id = r.id AND a.account_id = $1 AND NOT a.deleted) ORDER BY r.id",
    ).execute(Tuple.of(accountId)).coAwait().map { it.getLong("id") }

    suspend fun roles(client: SqlClient): List<JsonObject> = client.query(
        "SELECT * FROM tb_account_role_definitions WHERE NOT deleted ORDER BY id",
    ).execute().coAwait().map { roleJson(it) }

    suspend fun policies(client: SqlClient, accountId: Long): List<Map<String, Boolean>> = client.preparedQuery(
        "SELECT r.permissions FROM tb_account_role_definitions r WHERE NOT r.deleted AND (r.is_default OR EXISTS (" +
            "SELECT 1 FROM tb_account_role_assignments a WHERE a.role_id = r.id AND a.account_id = $1 AND NOT a.deleted))",
    ).execute(Tuple.of(accountId)).coAwait().map { row ->
        row.getJsonObject("permissions").map.mapValues { it.value == true }
    }

    /** [policies]와 같은 역할을 판정 근거 표시용 ID와 이름과 함께 읽는다. */
    suspend fun policyRoles(client: SqlClient, accountId: Long): List<PolicyRole> = client.preparedQuery(
        "SELECT r.id, r.name, r.is_default, r.permissions FROM tb_account_role_definitions r " +
            "WHERE NOT r.deleted AND (r.is_default OR EXISTS (" +
            "SELECT 1 FROM tb_account_role_assignments a " +
            "WHERE a.role_id = r.id AND a.account_id = $1 AND NOT a.deleted)) ORDER BY r.id",
    ).execute(Tuple.of(accountId)).coAwait().map { row ->
        PolicyRole(
            roleId = row.getLong("id"),
            roleName = row.getString("name"),
            permissions = row.getJsonObject("permissions").map.mapValues { it.value == true },
            isDefault = row.getBoolean("is_default"),
        )
    }

    suspend fun create(client: SqlClient, name: String, permissions: JsonObject): JsonObject {
        val row = client.preparedQuery(
            "INSERT INTO tb_account_role_definitions (name, permissions) VALUES ($1, $2::jsonb) RETURNING *",
        ).execute(Tuple.of(name, permissions)).coAwait().first()
        outbox.enqueue(client, roleEvent(row))
        return roleJson(row)
    }

    suspend fun update(client: SqlClient, id: Long, name: String, permissions: JsonObject): JsonObject {
        val row = client.preparedQuery(
            "UPDATE tb_account_role_definitions SET name = $2, permissions = $3::jsonb, " +
                "state_occurred_at = GREATEST(clock_timestamp(), state_occurred_at + INTERVAL '1 microsecond') " +
                "WHERE id = $1 AND NOT deleted AND (NOT is_default OR name = $2) RETURNING *",
        ).execute(Tuple.of(id, name, permissions)).coAwait().firstOrNull()
            ?: throw IllegalArgumentException("역할을 찾을 수 없거나 기본 역할 이름을 변경할 수 없습니다.")
        val grantsOperations = AccountRolePolicy.operationalActions.any { permissions.getBoolean(it) == true }
        require(!row.getBoolean("is_default") || !grantsOperations) {
            "기본 역할에는 운영 권한을 부여할 수 없습니다."
        }
        outbox.enqueue(client, roleEvent(row))
        return roleJson(row)
    }

    suspend fun delete(client: SqlClient, id: Long) {
        val row = client.preparedQuery(
            "UPDATE tb_account_role_definitions SET deleted = TRUE, " +
                "state_occurred_at = GREATEST(clock_timestamp(), state_occurred_at + INTERVAL '1 microsecond') " +
                "WHERE id = $1 AND NOT is_default RETURNING *",
        ).execute(Tuple.of(id)).coAwait().firstOrNull()
            ?: throw IllegalArgumentException("기본 역할은 삭제할 수 없으며 대상 역할이 존재해야 합니다.")
        outbox.enqueue(client, roleEvent(row))
    }

    suspend fun assign(client: SqlClient, roleId: Long, accountId: Long, deleted: Boolean) {
        require(roleId > 0 && accountId > 0) { "역할과 사용자 ID는 양수여야 합니다." }
        val role = client.preparedQuery(
            "SELECT id FROM tb_account_role_definitions WHERE id = $1 AND NOT deleted AND NOT is_default FOR SHARE",
        ).execute(Tuple.of(roleId)).coAwait().firstOrNull()
        require(role != null) { "직접 부여할 수 있는 역할을 찾을 수 없습니다." }
        val row = client.preparedQuery(
            "INSERT INTO tb_account_role_assignments (account_id, role_id, deleted) VALUES ($1, $2, $3) " +
                "ON CONFLICT (account_id, role_id) DO UPDATE SET deleted = EXCLUDED.deleted, " +
                "state_occurred_at = GREATEST(clock_timestamp(), tb_account_role_assignments.state_occurred_at + INTERVAL '1 microsecond') RETURNING *",
        ).execute(Tuple.of(accountId, roleId, deleted)).coAwait().first()
        outbox.enqueue(client, assignmentEvent(row))
    }

    suspend fun publishSnapshot(client: SqlClient) {
        val roles = client.query("SELECT * FROM tb_account_role_definitions ORDER BY id FOR SHARE").execute().coAwait()
        for (row in roles) outbox.enqueue(client, roleEvent(row))
        val assignments = client.query(
            "SELECT * FROM tb_account_role_assignments ORDER BY account_id, role_id FOR SHARE",
        ).execute().coAwait()
        for (row in assignments) outbox.enqueue(client, assignmentEvent(row))
    }

    private fun roleJson(row: Row): JsonObject = JsonObject().put("id", row.getLong("id"))
        .put("name", row.getString("name")).put("permissions", row.getJsonObject("permissions"))
        .put("isDefault", row.getBoolean("is_default"))

    private fun roleEvent(row: Row): PreferenceEvent {
        val version = row.getOffsetDateTime("state_occurred_at").toInstant()
        return PreferenceEvent(
            PreferenceTopics.ACCOUNT_ROLE_TOPIC,
            "role:${row.getLong("id")}",
            basePayload("ROLE", version).mergeIn(roleJson(row)).put("roleId", row.getLong("id"))
                .put("deleted", row.getBoolean("deleted")),
            version,
        )
    }

    private fun assignmentEvent(row: Row): PreferenceEvent {
        val version = row.getOffsetDateTime("state_occurred_at").toInstant()
        val accountId = row.getLong("account_id")
        val roleId = row.getLong("role_id")
        return PreferenceEvent(
            PreferenceTopics.ACCOUNT_ROLE_TOPIC,
            "assignment:$accountId:$roleId",
            basePayload("ASSIGNMENT", version).put("roleId", roleId).put("accountId", accountId)
                .put("deleted", row.getBoolean("deleted")),
            version,
        )
    }
}
