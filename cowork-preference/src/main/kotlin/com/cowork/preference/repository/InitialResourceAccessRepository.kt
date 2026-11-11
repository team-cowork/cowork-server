package com.cowork.preference.repository

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class InitialResourceAccessRepository {
    /** The caller's transaction also owns the grant, outbox and checkpoint; failures must roll this claim back. */
    suspend fun claim(client: SqlClient, resourceType: String, resourceId: Long): Boolean = client.preparedQuery(
        "INSERT INTO tb_initial_resource_access_ledger (resource_type, resource_id) VALUES (\$1, \$2) " +
            "ON CONFLICT DO NOTHING",
    ).execute(Tuple.of(resourceType, resourceId)).coAwait().rowCount() == 1

    suspend fun hasChannelPolicyHistory(client: SqlClient, teamId: Long, channelId: Long): Boolean =
        client.preparedQuery(
            "SELECT EXISTS (SELECT 1 FROM tb_channel_role_policies WHERE team_id = \$1 AND channel_id = \$2 " +
                "UNION ALL SELECT 1 FROM tb_channel_role_policy_tombstones " +
                "WHERE team_id = \$1 AND channel_id = \$2) AS present",
        ).execute(Tuple.of(teamId, channelId)).coAwait().first().getBoolean("present")

    suspend fun hasProjectAccessHistory(client: SqlClient, projectId: Long): Boolean = client.preparedQuery(
        "SELECT EXISTS (SELECT 1 FROM project_role_definition WHERE project_id = \$1) AS present",
    ).execute(Tuple.of(projectId)).coAwait().first().getBoolean("present")
}
