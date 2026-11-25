package com.cowork.preference.domain.channelrole.repository

import com.cowork.preference.domain.channelrole.model.ChannelAccessPermissions
import com.cowork.preference.domain.teamrole.repository.TeamAccessInputs
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceTopics
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class ChannelAccessStateRepository(private val outbox: PreferenceOutboxRepository) {
    /** 채널 하나의 모든 멤버 판정 결과를 다시 계산한다. 팀 채널이 아니면 아무것도 하지 않는다. */
    suspend fun refreshChannel(client: SqlClient, channelId: Long) {
        val teamId = client.preparedQuery(
            "SELECT team_id FROM tb_channel_lifecycle_projections WHERE channel_id = $1",
        ).execute(Tuple.of(channelId)).coAwait().firstOrNull()?.getLong("team_id") ?: return
        refresh(client, teamId, channelId, null)
    }

    /** 팀의 모든 채널 판정 결과를 다시 계산한다. [accountId]가 null이면 팀의 모든 멤버를 다시 계산한다. */
    suspend fun refreshTeam(client: SqlClient, teamId: Long, accountId: Long? = null) =
        refresh(client, teamId, null, accountId)

    private suspend fun refresh(client: SqlClient, teamId: Long, channelId: Long?, accountId: Long?) {
        // The caller's ordered outbox transaction serializes recomputation, so an older decision cannot be published last.
        val channels = client.preparedQuery(
            "SELECT channel_id, deleted, is_private, channel_type FROM tb_channel_lifecycle_projections " +
                "WHERE team_id = $1 AND ($2::bigint IS NULL OR channel_id = $2) ORDER BY channel_id",
        ).execute(Tuple.of(teamId, channelId)).coAwait().toList()
        if (channels.isEmpty()) return
        val inputs = TeamAccessInputs.load(client, teamId, accountId)
        val overwrites = client.preparedQuery(
            "SELECT channel_id, role_id, permissions FROM tb_channel_role_policies " +
                "WHERE team_id = $1 AND ($2::bigint IS NULL OR channel_id = $2)",
        ).execute(Tuple.of(teamId, channelId)).coAwait().groupBy { it.getLong("channel_id") }
            .mapValues { (_, rows) ->
                rows.associate { row ->
                    row.getLong("role_id") to row.getJsonObject("permissions").map
                        .filterValues { it is Boolean }
                        .mapValues { it.value as Boolean }
                }
            }
        val existing = client.preparedQuery(
            "SELECT * FROM tb_channel_access_states WHERE team_id = $1 " +
                "AND ($2::bigint IS NULL OR channel_id = $2) AND ($3::bigint IS NULL OR account_id = $3)",
        ).execute(Tuple.of(teamId, channelId, accountId)).coAwait()
            .groupBy { it.getLong("channel_id") }
            .mapValues { (_, rows) -> rows.associateBy { it.getLong("account_id") } }
        channels.forEach { channel ->
            val id = channel.getLong("channel_id")
            val previousStates = existing[id].orEmpty()
            val deleted = channel.getBoolean("deleted")
            // A deleted channel only needs deny records for pairs that were already published.
            val memberIds = if (deleted) previousStates.keys else inputs.memberIds + previousStates.keys
            memberIds.sorted().forEach { memberId ->
                val result = ChannelAccessPermissions.resolve(
                    inputs.roles(memberId),
                    overwrites[id].orEmpty(),
                    active = inputs.isActive(memberId),
                    deleted = deleted,
                    isPrivate = channel.getBoolean("is_private"),
                    channelType = channel.getString("channel_type"),
                )
                upsert(client, teamId, id, memberId, result, inputs, previousStates[memberId])
            }
        }
    }

    private suspend fun upsert(
        client: SqlClient,
        teamId: Long,
        channelId: Long,
        accountId: Long,
        result: ChannelAccessPermissions.Result,
        inputs: TeamAccessInputs,
        previous: Row?,
    ) {
        val permissions = JsonObject(result.permissions)
        val membershipVersion = inputs.membershipVersion(accountId)
        val authorizationVersion = inputs.authorizationVersion(accountId)
        if (previous != null && previous.getJsonObject("permissions") == permissions &&
            previous.getBoolean("can_read_private") == result.readPrivate &&
            previous.getOffsetDateTime("source_membership_version").toInstant() == membershipVersion.toInstant() &&
            previous.getOffsetDateTime("source_authorization_version")?.toInstant() == authorizationVersion?.toInstant()
        ) {
            return
        }
        val rows = client.preparedQuery(
            """
            INSERT INTO tb_channel_access_states
                (channel_id, account_id, team_id, permissions, can_read_private, source_membership_version,
                 source_authorization_version, state_occurred_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, clock_timestamp())
            ON CONFLICT (channel_id, account_id) DO UPDATE SET
                permissions = EXCLUDED.permissions,
                can_read_private = EXCLUDED.can_read_private,
                source_membership_version = EXCLUDED.source_membership_version,
                source_authorization_version = EXCLUDED.source_authorization_version,
                state_occurred_at = GREATEST(clock_timestamp(), tb_channel_access_states.state_occurred_at + INTERVAL '1 microsecond')
            RETURNING *
            """.trimIndent(),
        ).execute(
            Tuple.tuple().addLong(channelId).addLong(accountId).addLong(teamId).addValue(permissions)
                .addBoolean(result.readPrivate).addOffsetDateTime(membershipVersion)
                .addOffsetDateTime(authorizationVersion),
        ).coAwait()
        rows.forEach { outbox.enqueue(client, event(it)) }
    }

    suspend fun publishSnapshot(client: SqlClient) {
        val teams = client.query(
            "SELECT DISTINCT team_id FROM tb_channel_lifecycle_projections WHERE team_id IS NOT NULL " +
                "UNION SELECT team_id FROM tb_channel_access_states ORDER BY team_id",
        ).execute().coAwait()
        teams.forEach { refreshTeam(client, it.getLong("team_id")) }
        val states = client.query("SELECT * FROM tb_channel_access_states ORDER BY channel_id, account_id")
            .execute().coAwait()
        // Changed pairs above were already published as live records; only the full replay carries the snapshot flag.
        states.forEach { outbox.enqueue(client, event(it, snapshot = true)) }
    }

    private fun event(row: Row, snapshot: Boolean = false): PreferenceEvent {
        val channelId = row.getLong("channel_id")
        val accountId = row.getLong("account_id")
        val version = row.getOffsetDateTime("state_occurred_at").toInstant()
        return PreferenceEvent(
            PreferenceTopics.CHANNEL_ACCESS_STATE_TOPIC,
            "$channelId:$accountId",
            JsonObject().put("eventType", "UPSERT")
                .put("channelId", channelId).put("accountId", accountId).put("teamId", row.getLong("team_id"))
                .put("permissions", row.getJsonObject("permissions"))
                .put("readPrivate", row.getBoolean("can_read_private"))
                .put(
                    "sourceMembershipVersion",
                    row.getOffsetDateTime("source_membership_version").toInstant().toString(),
                )
                .put(
                    "sourceAuthorizationVersion",
                    row.getOffsetDateTime("source_authorization_version")?.toInstant()?.toString(),
                )
                .put("occurredAt", version.toString())
                .put("snapshot", snapshot),
            version,
        )
    }
}
