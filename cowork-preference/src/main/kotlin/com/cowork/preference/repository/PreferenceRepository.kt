package com.cowork.preference.repository

import com.cowork.preference.domain.ResourceType
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import io.vertx.core.json.JsonObject

class PreferenceRepository(private val pool: Pool) {

    suspend fun findSettings(resourceId: Long, resourceType: ResourceType): JsonObject? {
        val rows = pool.preparedQuery(
            "SELECT settings FROM resource_setting WHERE resource_id = \$1 AND resource_type = \$2::resource_type"
        ).execute(Tuple.of(resourceId, resourceType.name)).coAwait()
        return rows.firstOrNull()?.getJsonObject("settings")
    }

    suspend fun upsertSettings(resourceId: Long, resourceType: ResourceType, settings: JsonObject): JsonObject? {
        val rows = pool.preparedQuery(
            """
            INSERT INTO resource_setting (resource_id, resource_type, settings)
            VALUES (${'$'}1, ${'$'}2::resource_type, ${'$'}3::jsonb)
            ON CONFLICT (resource_id, resource_type)
            DO UPDATE SET settings = resource_setting.settings || EXCLUDED.settings
            RETURNING settings
            """.trimIndent()
        ).execute(Tuple.of(resourceId, resourceType.name, settings)).coAwait()
        return rows.firstOrNull()?.getJsonObject("settings")
    }

    /** status_expires_at이 현재 시각보다 이전인 ACCOUNT 설정 목록 조회 */
    suspend fun findExpiredAccountStatuses(): List<Pair<Long, String>> {
        val rows = pool.preparedQuery(
            """
            SELECT resource_id, settings->>'status' AS status
            FROM resource_setting
            WHERE resource_type = 'ACCOUNT'
              AND settings->>'status_expires_at' IS NOT NULL
              AND (settings->>'status_expires_at')::timestamptz <= now()
            """.trimIndent()
        ).execute().coAwait()
        return rows.mapNotNull { row ->
            val id = row.getLong("resource_id") ?: return@mapNotNull null
            val status = row.getString("status") ?: return@mapNotNull null
            id to status
        }
    }

    /** status_expires_at 및 status 필드를 배치 제거 (만료 후 클리어) */
    suspend fun clearExpiredStatuses(resourceIds: List<Long>) {
        if (resourceIds.isEmpty()) return
        pool.preparedQuery(
            """
            UPDATE resource_setting
            SET settings = settings - 'status' - 'status_expires_at'
            WHERE resource_id = ANY(${'$'}1) AND resource_type = 'ACCOUNT'
            """.trimIndent()
        ).execute(Tuple.of(resourceIds.toTypedArray<Long>())).coAwait()
    }
}
