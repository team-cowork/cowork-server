package com.cowork.preference.repository

import com.cowork.preference.domain.ProjectLifecycleProjection
import com.cowork.preference.messaging.ProjectLifecycleEvent
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.ZoneOffset

class ProjectLifecycleProjectionRepository {
    /** 높은 source version을 우선하고 같은 version에서는 삭제를 우선한다. 반영되었으면 true를 반환한다. */
    suspend fun apply(client: SqlClient, event: ProjectLifecycleEvent): Boolean {
        val rows = client.preparedQuery(
            """
            INSERT INTO tb_project_lifecycle_projections (project_id, team_id, deleted, source_occurred_at, created_by)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5)
            ON CONFLICT (project_id)
            DO UPDATE SET
                team_id = EXCLUDED.team_id,
                created_by = COALESCE(EXCLUDED.created_by, tb_project_lifecycle_projections.created_by),
                deleted = EXCLUDED.deleted,
                source_occurred_at = EXCLUDED.source_occurred_at
            WHERE EXCLUDED.source_occurred_at > tb_project_lifecycle_projections.source_occurred_at
               OR (
                    EXCLUDED.source_occurred_at = tb_project_lifecycle_projections.source_occurred_at
                    AND ((EXCLUDED.deleted = TRUE AND tb_project_lifecycle_projections.deleted = FALSE)
                         OR (EXCLUDED.deleted = tb_project_lifecycle_projections.deleted AND tb_project_lifecycle_projections.created_by IS NULL AND EXCLUDED.created_by IS NOT NULL))
               )
            """.trimIndent(),
        ).execute(
            Tuple.of(event.projectId, event.teamId, event.deleted, event.occurredAt.atOffset(ZoneOffset.UTC), event.createdBy),
        ).coAwait()
        return rows.rowCount() == 1
    }

    /** authorization guard용 단순 조회다. 잠금이 없어 projection consumer의 갱신 트랜잭션을 기다리지 않는다. */
    suspend fun findForRead(client: SqlClient, projectId: Long): ProjectLifecycleProjection? {
        val rows = client.preparedQuery(
            """
            SELECT project_id, team_id, deleted, source_occurred_at, created_by
            FROM tb_project_lifecycle_projections
            WHERE project_id = ${'$'}1
            """.trimIndent(),
        ).execute(Tuple.of(projectId)).coAwait()
        return rows.firstOrNull()?.let {
            ProjectLifecycleProjection(
                projectId = it.getLong("project_id"),
                teamId = it.getLong("team_id"),
                deleted = it.getBoolean("deleted"),
                sourceOccurredAt = it.getOffsetDateTime("source_occurred_at").toInstant(),
                createdBy = it.getLong("created_by"),
            )
        }
    }
}
