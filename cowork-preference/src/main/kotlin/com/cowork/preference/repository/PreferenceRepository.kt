package com.cowork.preference.repository

import com.cowork.preference.domain.ResourceType
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant

data class PreferenceSettingsUpdate(val settings: JsonObject, val previousStatus: String?, val updatedAt: Instant)

class PreferenceRepository(private val pool: Pool) {

    suspend fun findSettings(resourceId: Long, resourceType: ResourceType): JsonObject? {
        val rows = pool.preparedQuery(
            "SELECT settings FROM resource_setting WHERE resource_id = \$1 AND resource_type = \$2::resource_type",
        ).execute(Tuple.of(resourceId, resourceType.name)).coAwait()
        return rows.firstOrNull()?.getJsonObject("settings")
    }

    /** 여러 리소스의 설정을 한 번의 쿼리로 조회 (N+1 방지용 벌크 조회) */
    suspend fun findSettingsForResources(resourceIds: List<Long>, resourceType: ResourceType): Map<Long, JsonObject> {
        if (resourceIds.isEmpty()) return emptyMap()
        val rows = pool.preparedQuery(
            "SELECT resource_id, settings FROM resource_setting WHERE resource_id = ANY(\$1) AND resource_type = \$2::resource_type",
        ).execute(Tuple.of(resourceIds.toTypedArray<Long>(), resourceType.name)).coAwait()
        return rows.associate { it.getLong("resource_id") to it.getJsonObject("settings") }
    }

    internal suspend fun upsertSettings(
        client: SqlClient,
        resourceId: Long,
        resourceType: ResourceType,
        settings: JsonObject,
        fetchPreviousStatus: Boolean = false,
    ): PreferenceSettingsUpdate {
        val query = if (fetchPreviousStatus) {
            """
            WITH before_update AS (
                SELECT settings->>'status' AS prev_status
                FROM resource_setting
                WHERE resource_id = ${'$'}1 AND resource_type = ${'$'}2::resource_type
            )
            INSERT INTO resource_setting (resource_id, resource_type, settings)
            VALUES (${'$'}1, ${'$'}2::resource_type, ${'$'}3::jsonb)
            ON CONFLICT (resource_id, resource_type)
            DO UPDATE SET settings = resource_setting.settings || EXCLUDED.settings
            RETURNING settings, updated_at, (SELECT prev_status FROM before_update LIMIT 1) AS previous_status
            """.trimIndent()
        } else {
            """
            INSERT INTO resource_setting (resource_id, resource_type, settings)
            VALUES (${'$'}1, ${'$'}2::resource_type, ${'$'}3::jsonb)
            ON CONFLICT (resource_id, resource_type)
            DO UPDATE SET settings = resource_setting.settings || EXCLUDED.settings
            RETURNING settings, updated_at
            """.trimIndent()
        }
        val rows = client.preparedQuery(query).execute(Tuple.of(resourceId, resourceType.name, settings)).coAwait()
        val row = rows.first()
        return PreferenceSettingsUpdate(
            settings = row.getJsonObject("settings"),
            previousStatus = if (fetchPreviousStatus) row.getString("previous_status") else null,
            updatedAt = row.getOffsetDateTime("updated_at").toInstant(),
        )
    }

    /** status_expires_at이 현재 시각보다 이전인 ACCOUNT 설정 목록 조회 */
    internal suspend fun findExpiredAccountStatuses(client: SqlClient): List<Pair<Long, String>> {
        val rows = client.preparedQuery(
            """
            SELECT resource_id, settings->>'status' AS status
            FROM resource_setting
            WHERE resource_type = 'ACCOUNT'
              AND settings->>'status_expires_at' IS NOT NULL
              AND (settings->>'status_expires_at')::timestamptz <= now()
            FOR UPDATE
            """.trimIndent(),
        ).execute().coAwait()
        return rows.mapNotNull { row ->
            val id = row.getLong("resource_id") ?: return@mapNotNull null
            val status = row.getString("status") ?: return@mapNotNull null
            id to status
        }
    }

    /** status_expires_at 및 status 필드를 배치 제거 (만료 후 클리어) */
    internal suspend fun clearExpiredStatuses(client: SqlClient, resourceIds: List<Long>) {
        if (resourceIds.isEmpty()) return
        client.preparedQuery(
            """
            UPDATE resource_setting
            SET settings = settings - 'status' - 'status_expires_at'
            WHERE resource_id = ANY(${'$'}1) AND resource_type = 'ACCOUNT'
            """.trimIndent(),
        ).execute(Tuple.of(resourceIds.toTypedArray<Long>())).coAwait()
    }
}
