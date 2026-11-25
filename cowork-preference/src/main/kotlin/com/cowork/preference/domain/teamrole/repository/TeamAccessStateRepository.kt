package com.cowork.preference.domain.teamrole.repository

import com.cowork.preference.domain.teamrole.model.TeamAccessPermissions
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceTopics
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class TeamAccessStateRepository(private val outbox: PreferenceOutboxRepository) {
    /** 팀 권한 판정 결과를 다시 계산한다. [accountId]가 null이면 팀의 모든 멤버를 다시 계산한다. */
    suspend fun refresh(client: SqlClient, teamId: Long, accountId: Long? = null) {
        // The caller's ordered outbox transaction serializes recomputation, so an older decision cannot be published last.
        val inputs = TeamAccessInputs.load(client, teamId, accountId)
        val existing = client.preparedQuery(
            "SELECT * FROM tb_team_access_states WHERE team_id = $1 AND ($2::bigint IS NULL OR account_id = $2)",
        ).execute(Tuple.of(teamId, accountId)).coAwait().associateBy { it.getLong("account_id") }
        (inputs.memberIds + existing.keys).sorted().forEach { memberId ->
            val permissions = TeamAccessPermissions.resolve(inputs.roles(memberId), inputs.isActive(memberId))
            val membershipVersion = inputs.membershipVersion(memberId)
            val authorizationVersion = inputs.authorizationVersion(memberId)
            val previous = existing[memberId]
            if (previous != null && previous.getJsonObject("permissions") == JsonObject(permissions) &&
                previous.getOffsetDateTime("source_membership_version").toInstant() == membershipVersion.toInstant() &&
                previous.getOffsetDateTime("source_authorization_version")?.toInstant() ==
                authorizationVersion?.toInstant()
            ) {
                return@forEach
            }
            val rows = client.preparedQuery(
                """
                INSERT INTO tb_team_access_states
                    (team_id, account_id, permissions, source_membership_version, source_authorization_version,
                     state_occurred_at)
                VALUES ($1, $2, $3, $4, $5, clock_timestamp())
                ON CONFLICT (team_id, account_id) DO UPDATE SET
                    permissions = EXCLUDED.permissions,
                    source_membership_version = EXCLUDED.source_membership_version,
                    source_authorization_version = EXCLUDED.source_authorization_version,
                    state_occurred_at = GREATEST(clock_timestamp(), tb_team_access_states.state_occurred_at + INTERVAL '1 microsecond')
                RETURNING *
                """.trimIndent(),
            ).execute(
                Tuple.tuple().addLong(teamId).addLong(memberId).addValue(JsonObject(permissions))
                    .addOffsetDateTime(membershipVersion).addOffsetDateTime(authorizationVersion),
            ).coAwait()
            rows.forEach { outbox.enqueue(client, event(it)) }
        }
    }

    suspend fun publishSnapshot(client: SqlClient) {
        val teams = client.query(
            "SELECT DISTINCT team_id FROM tb_team_member_projections " +
                "UNION SELECT team_id FROM tb_team_access_states ORDER BY team_id",
        ).execute().coAwait()
        teams.forEach { refresh(client, it.getLong("team_id")) }
        val states = client.query("SELECT * FROM tb_team_access_states ORDER BY team_id, account_id")
            .execute().coAwait()
        states.forEach { outbox.enqueue(client, event(it)) }
    }

    private fun event(row: Row): PreferenceEvent {
        val teamId = row.getLong("team_id")
        val accountId = row.getLong("account_id")
        val version = row.getOffsetDateTime("state_occurred_at").toInstant()
        return PreferenceEvent(
            PreferenceTopics.TEAM_ACCESS_STATE_TOPIC,
            "$teamId:$accountId",
            JsonObject().put("eventType", "UPSERT")
                .put("teamId", teamId).put("accountId", accountId)
                .put("permissions", row.getJsonObject("permissions"))
                .put(
                    "sourceMembershipVersion",
                    row.getOffsetDateTime("source_membership_version").toInstant().toString(),
                )
                .put(
                    "sourceAuthorizationVersion",
                    row.getOffsetDateTime("source_authorization_version")?.toInstant()?.toString(),
                )
                .put("occurredAt", version.toString()),
            version,
        )
    }
}
