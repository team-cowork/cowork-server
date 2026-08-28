package com.cowork.preference.repository

import com.cowork.preference.domain.ResourceType
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant

data class PreferenceSettingsUpdate(
    val settings: JsonObject,
    val previousStatus: String?,
    val updatedAt: Instant,
    val stateOccurredAt: Instant,
)

data class GithubRepoSettingState(val repoId: Long, val settings: JsonObject?, val stateOccurredAt: Instant)

class PreferenceRepository(private val pool: Pool) {

    suspend fun findSettings(resourceId: Long, resourceType: ResourceType): JsonObject? {
        val rows = pool.preparedQuery(
            "SELECT settings FROM resource_setting WHERE resource_id = \$1 AND resource_type = \$2::resource_type",
        ).execute(Tuple.of(resourceId, resourceType.name)).coAwait()
        return rows.firstOrNull()?.getJsonObject("settings")
    }

    suspend fun findSettingsForResources(resourceIds: List<Long>, resourceType: ResourceType): Map<Long, JsonObject> {
        if (resourceIds.isEmpty()) return emptyMap()
        val rows = pool.preparedQuery(
            "SELECT resource_id, settings FROM resource_setting " +
                "WHERE resource_id = ANY(\$1) AND resource_type = \$2::resource_type",
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
            RETURNING settings, updated_at, state_occurred_at,
                (SELECT prev_status FROM before_update LIMIT 1) AS previous_status
            """.trimIndent()
        } else {
            """
            INSERT INTO resource_setting (resource_id, resource_type, settings)
            VALUES (${'$'}1, ${'$'}2::resource_type, ${'$'}3::jsonb)
            ON CONFLICT (resource_id, resource_type)
            DO UPDATE SET settings = resource_setting.settings || EXCLUDED.settings
            RETURNING settings, updated_at, state_occurred_at
            """.trimIndent()
        }
        val rows = client.preparedQuery(query).execute(Tuple.of(resourceId, resourceType.name, settings)).coAwait()
        val row = rows.first()
        return PreferenceSettingsUpdate(
            settings = row.getJsonObject("settings"),
            previousStatus = if (fetchPreviousStatus) row.getString("previous_status") else null,
            updatedAt = row.getOffsetDateTime("updated_at").toInstant(),
            stateOccurredAt = row.getOffsetDateTime("state_occurred_at").toInstant(),
        )
    }

    internal suspend fun upsertGithubRepoSettings(
        client: SqlClient,
        repoId: Long,
        settings: JsonObject,
    ): PreferenceSettingsUpdate {
        val rows = client.preparedQuery(
            """
            WITH removed_tombstone AS (
                DELETE FROM tb_github_repo_setting_tombstones
                WHERE repo_id = ${'$'}1
                RETURNING source_occurred_at
            ),
            next_state AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    COALESCE(
                        (SELECT source_occurred_at + INTERVAL '1 microsecond' FROM removed_tombstone),
                        '-infinity'::timestamptz
                    )
                ) AS state_occurred_at
            )
            INSERT INTO resource_setting (resource_id, resource_type, settings, state_occurred_at)
            SELECT ${'$'}1, 'GITHUB_REPO'::resource_type, ${'$'}2::jsonb, state_occurred_at
            FROM next_state
            ON CONFLICT (resource_id, resource_type)
            DO UPDATE SET
                settings = resource_setting.settings || EXCLUDED.settings,
                state_occurred_at = EXCLUDED.state_occurred_at
            RETURNING settings, updated_at, state_occurred_at
            """.trimIndent(),
        ).execute(Tuple.of(repoId, settings)).coAwait()
        val row = rows.first()
        return PreferenceSettingsUpdate(
            settings = row.getJsonObject("settings"),
            previousStatus = null,
            updatedAt = row.getOffsetDateTime("updated_at").toInstant(),
            stateOccurredAt = row.getOffsetDateTime("state_occurred_at").toInstant(),
        )
    }

    internal suspend fun findGithubRepoSettingPage(
        client: SqlClient,
        afterRepoId: Long,
        limit: Int,
    ): List<GithubRepoSettingState> {
        val rows = client.preparedQuery(
            """
            WITH active_states AS (
                SELECT resource_id AS repo_id, settings, state_occurred_at
                FROM resource_setting
                WHERE resource_type = 'GITHUB_REPO' AND resource_id > ${'$'}1
                ORDER BY resource_id
                LIMIT ${'$'}2
                FOR SHARE
            ),
            deleted_states AS (
                SELECT repo_id, NULL::jsonb AS settings, source_occurred_at AS state_occurred_at
                FROM tb_github_repo_setting_tombstones
                WHERE repo_id > ${'$'}1
                ORDER BY repo_id
                LIMIT ${'$'}2
                FOR SHARE
            ),
            latest_states AS (
                SELECT repo_id, settings, state_occurred_at FROM active_states
                UNION ALL
                SELECT repo_id, settings, state_occurred_at FROM deleted_states
            )
            SELECT repo_id, settings, state_occurred_at
            FROM latest_states
            ORDER BY repo_id
            LIMIT ${'$'}2
            """.trimIndent(),
        ).execute(Tuple.of(afterRepoId, limit)).coAwait()
        return rows.map { row ->
            GithubRepoSettingState(
                repoId = row.getLong("repo_id"),
                settings = row.getJsonObject("settings"),
                stateOccurredAt = row.getOffsetDateTime("state_occurred_at").toInstant(),
            )
        }
    }

    internal suspend fun deleteGithubRepoSettings(client: SqlClient, repoId: Long): Instant {
        val rows = client.preparedQuery(
            """
            WITH deleted_setting AS (
                DELETE FROM resource_setting
                WHERE resource_id = ${'$'}1 AND resource_type = 'GITHUB_REPO'
                RETURNING state_occurred_at
            ),
            next_tombstone AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    COALESCE(
                        (SELECT state_occurred_at + INTERVAL '1 microsecond' FROM deleted_setting),
                        '-infinity'::timestamptz
                    ),
                    COALESCE(
                        (
                            SELECT source_occurred_at + INTERVAL '1 microsecond'
                            FROM tb_github_repo_setting_tombstones
                            WHERE repo_id = ${'$'}1
                        ),
                        '-infinity'::timestamptz
                    )
                ) AS source_occurred_at
            )
            INSERT INTO tb_github_repo_setting_tombstones (repo_id, source_occurred_at)
            SELECT ${'$'}1, source_occurred_at FROM next_tombstone
            ON CONFLICT (repo_id) DO UPDATE SET
                source_occurred_at = GREATEST(
                    EXCLUDED.source_occurred_at,
                    tb_github_repo_setting_tombstones.source_occurred_at + INTERVAL '1 microsecond',
                    clock_timestamp()
                )
            RETURNING source_occurred_at AS state_occurred_at
            """.trimIndent(),
        ).execute(Tuple.of(repoId)).coAwait()
        return rows.first().getOffsetDateTime("state_occurred_at").toInstant()
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
