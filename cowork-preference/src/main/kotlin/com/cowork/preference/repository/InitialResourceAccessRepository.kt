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
        "SELECT EXISTS (SELECT 1 FROM project_role_definition WHERE project_id = \$1) " +
            // 구버전 Preference가 seed를 무시한 뒤 수동 회수한 경우를 보수적으로 보호한다.
            // TODO(#596): 2026-11-01 05:00 KST 이후 구버전 중단·전체 seed ledger 처리·snapshot 확인 후 derived 이력 조건을 제거한다.
            // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
            "OR EXISTS (SELECT 1 FROM tb_project_access_states WHERE project_id = \$1) AS present",
    ).execute(Tuple.of(projectId)).coAwait().first().getBoolean("present")
}
