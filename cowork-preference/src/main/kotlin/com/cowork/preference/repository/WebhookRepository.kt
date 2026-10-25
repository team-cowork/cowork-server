package com.cowork.preference.repository

import com.cowork.preference.domain.LegacyWebhookManifest
import com.cowork.preference.domain.WebhookMigrationIds
import com.cowork.preference.domain.WebhookMigrationMetadata
import com.cowork.preference.domain.WebhookRequestException
import com.cowork.preference.domain.Webhook
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.time.ZoneOffset

data class WebhookOperation(
    val requestHash: String,
    val response: JsonObject,
    val secretCiphertext: String?,
    val createdAt: Instant,
)

class WebhookRepository {
    suspend fun list(client: SqlClient, channelId: Long): List<Webhook> = client.preparedQuery(
        "SELECT * FROM tb_webhooks WHERE channel_id = \$1 AND NOT deleted ORDER BY id",
    ).execute(Tuple.of(channelId)).coAwait().map { it.webhook() }

    suspend fun findForUpdate(client: SqlClient, webhookId: Long): Webhook? = client.preparedQuery(
        "SELECT * FROM tb_webhooks WHERE id = \$1 FOR UPDATE",
    ).execute(Tuple.of(webhookId)).coAwait().firstOrNull()?.webhook()

    suspend fun now(client: SqlClient): Instant = client.query("SELECT clock_timestamp() AS now")
        .execute().coAwait().first().getOffsetDateTime("now").toInstant()

    suspend fun create(client: SqlClient, channelId: Long, actorId: Long, input: JsonObject, secretHash: String?): Webhook {
        // TODO(#625): rollback 중 Channel의 낮은 AUTO_INCREMENT와 충돌하지 않게 분리한다.
        // v20261026.0 뒤 추가 릴리즈까지 rollback 없음을 확인하거나 확인이 어려우면 2026-11-01 이후 제거 가능하다.
        // 자동 만료하지 않으며 임시 하한 설정 코드를 제거해도 이미 올린 sequence 값은 되돌리지 않는다.
        client.query("SELECT setval(pg_get_serial_sequence('tb_webhooks', 'id'), " +
            "GREATEST((SELECT last_value FROM tb_webhooks_id_seq), ${WebhookMigrationIds.FIRST_OWNER_ID - 1}), true)")
            .execute().coAwait()
        val id = client.query("SELECT nextval(pg_get_serial_sequence('tb_webhooks', 'id')) AS id")
            .execute().coAwait().first().getLong("id")
        if (!WebhookMigrationIds.isOwner(id)) throw WebhookRequestException(503, "웹훅 식별자를 발급할 수 없습니다.")
        val row = client.preparedQuery(
            """
            INSERT INTO tb_webhooks (channel_id, name, avatar_url, is_secure, secret_hash, is_active, created_by, id)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8) RETURNING *
            """.trimIndent(),
        ).execute(Tuple.of(channelId, input.getString("name"), input.getString("avatarUrl"),
            input.getBoolean("isSecure"), secretHash, input.getBoolean("isActive"), actorId, id)).coAwait().first()
        return row.webhook()
    }

    suspend fun patch(client: SqlClient, webhook: Webhook, input: JsonObject): Webhook = client.preparedQuery(
        """
        UPDATE tb_webhooks SET name = ${'$'}2, avatar_url = ${'$'}3, is_active = ${'$'}4,
            updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
        WHERE id = ${'$'}1 RETURNING *
        """.trimIndent(),
    ).execute(Tuple.of(webhook.id, input.getString("name", webhook.name),
        if (input.containsKey("avatarUrl")) input.getString("avatarUrl") else webhook.avatarUrl,
        input.getBoolean("isActive", webhook.isActive))).coAwait().first().webhook()

    suspend fun changeMode(client: SqlClient, webhook: Webhook, target: Boolean, secretHash: String?, now: Instant): Webhook =
        client.preparedQuery(
            """
            UPDATE tb_webhooks SET is_secure = ${'$'}2, secret_hash = ${'$'}3, mode_changed_at = ${'$'}4,
                updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
            WHERE id = ${'$'}1 RETURNING *
            """.trimIndent(),
        ).execute(Tuple.of(webhook.id, target, secretHash, now.atOffset(ZoneOffset.UTC))).coAwait().first().webhook()

    suspend fun delete(client: SqlClient, webhookId: Long): Webhook = client.preparedQuery(
        """
        UPDATE tb_webhooks SET deleted = true, is_active = false, secret_hash = NULL,
            updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
        WHERE id = ${'$'}1 RETURNING *
        """.trimIndent(),
    ).execute(Tuple.of(webhookId)).coAwait().first().webhook()

    suspend fun deleteChannel(client: SqlClient, channelId: Long): List<Webhook> = client.preparedQuery(
        """
        UPDATE tb_webhooks SET deleted = true, is_active = false, secret_hash = NULL,
            updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
        WHERE channel_id = ${'$'}1 AND NOT deleted RETURNING *
        """.trimIndent(),
    ).execute(Tuple.of(channelId)).coAwait().map { it.webhook() }

    suspend fun legacyActive(client: SqlClient, channelId: Long): Boolean {
        val row = client.preparedQuery(
            """
            SELECT CASE WHEN jsonb_typeof(settings #> '{webhook,is_active}') = 'boolean'
                THEN (settings #>> '{webhook,is_active}')::boolean ELSE true END AS is_active
            FROM resource_setting WHERE resource_id = ${'$'}1 AND resource_type = 'TEXT_CHANNEL'
            """.trimIndent(),
        ).execute(Tuple.of(channelId)).coAwait().firstOrNull()
        return row?.getBoolean("is_active") ?: true
    }

    /** First import only; guarded reconciliation is handled separately with durable migration metadata. */
    suspend fun importLegacy(client: SqlClient, webhook: Webhook): Webhook? {
        val result = client.preparedQuery(
            """
            INSERT INTO tb_webhooks
                (id, channel_id, name, avatar_url, is_secure, secret_hash, is_active, deleted,
                 created_by, created_at, updated_at)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8, ${'$'}9, ${'$'}10, ${'$'}11)
            ON CONFLICT (id) DO NOTHING RETURNING *
            """.trimIndent(),
        ).execute(Tuple.tuple(listOf(webhook.id, webhook.channelId, webhook.name, webhook.avatarUrl,
            webhook.isSecure, webhook.secretHash, webhook.isActive, webhook.deleted, webhook.createdBy,
            webhook.createdAt.atOffset(ZoneOffset.UTC), webhook.updatedAt.atOffset(ZoneOffset.UTC)))).coAwait()
        // The shared outbox transaction lock serializes imports and creates across replicas.
        client.query(
            "SELECT setval(pg_get_serial_sequence('tb_webhooks', 'id'), " +
                "GREATEST((SELECT last_value FROM tb_webhooks_id_seq), (SELECT COALESCE(MAX(id), 1) FROM tb_webhooks)), true)",
        ).execute().coAwait()
        return result.firstOrNull()?.webhook()
    }

    internal suspend fun applyLegacy(client: SqlClient, source: Webhook, isActive: Boolean): Webhook = client.preparedQuery(
        """
        UPDATE tb_webhooks SET name = ${'$'}2, avatar_url = ${'$'}3, is_secure = ${'$'}4, secret_hash = ${'$'}5,
            is_active = ${'$'}6, deleted = ${'$'}7,
            mode_changed_at = CASE WHEN is_secure <> ${'$'}4 THEN ${'$'}8 ELSE mode_changed_at END,
            updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond', ${'$'}8)
        WHERE id = ${'$'}1 RETURNING *
        """.trimIndent(),
    ).execute(Tuple.of(source.id, source.name, source.avatarUrl, source.isSecure, source.secretHash,
        isActive, source.deleted, source.updatedAt.atOffset(ZoneOffset.UTC))).coAwait().first().webhook()

    // TODO(#625): 구형 rollback 보정을 위한 임시 actor=0 metadata이며 공개 요청자는 양수만 허용한다.
    // v20261026.0 뒤 추가 릴리즈까지 rollback이 없거나 확인이 어려우면 2026-11-01 이후,
    // 전체 이관·격리 해소와 importer/readiness/publisher 제거를 함께 확인한 뒤 제거한다. 자동 만료하지 않는다.
    internal suspend fun migrationMetadata(client: SqlClient, id: Long): WebhookMigrationMetadata? =
        findOperation(client, 0, "legacy-webhook:$id")?.let { WebhookMigrationMetadata.from(it.response, it.requestHash) }

    internal suspend fun saveMigrationMetadata(client: SqlClient, metadata: WebhookMigrationMetadata) =
        saveInternalMetadata(client, "legacy-webhook:${metadata.webhookId}", metadata.sourceHash, metadata.json())

    internal suspend fun channelMigrationMetadata(client: SqlClient, channelId: Long): List<WebhookMigrationMetadata> =
        client.preparedQuery(
            "SELECT response, request_hash FROM tb_webhook_operations WHERE actor_id = 0 " +
                "AND idempotency_key LIKE 'legacy-webhook:%' AND response->>'channelId' = ${'$'}1",
        ).execute(Tuple.of(channelId.toString())).coAwait().map {
            WebhookMigrationMetadata.from(it.getJsonObject("response"), it.getString("request_hash"))
        }

    internal suspend fun completedManifest(client: SqlClient, channelId: Long): LegacyWebhookManifest? =
        findOperation(client, 0, "legacy-webhook-manifest-complete:$channelId")?.let { LegacyWebhookManifest.from(it.response) }

    internal suspend fun manifestChunk(client: SqlClient, part: LegacyWebhookManifest): LegacyWebhookManifest? =
        findOperation(client, 0, "legacy-webhook-manifest-chunk:${part.channelId}:${part.chunkIndex}")
            ?.let { LegacyWebhookManifest.from(it.response) }

    internal suspend fun saveManifestChunk(client: SqlClient, part: LegacyWebhookManifest, hash: String) =
        saveInternalMetadata(client, "legacy-webhook-manifest-chunk:${part.channelId}:${part.chunkIndex}", hash, part.json())

    internal suspend fun manifestChunks(client: SqlClient, part: LegacyWebhookManifest): List<LegacyWebhookManifest> =
        client.preparedQuery(
            "SELECT response FROM tb_webhook_operations WHERE actor_id = 0 AND idempotency_key LIKE ${'$'}1 " +
                "AND response->>'snapshotId' = ${'$'}2 AND response->>'observedAt' = ${'$'}3",
        ).execute(Tuple.of("legacy-webhook-manifest-chunk:${part.channelId}:%", part.snapshotId, part.observedAt.toString()))
            .coAwait().map { LegacyWebhookManifest.from(it.getJsonObject("response")) }

    internal suspend fun saveCompletedManifest(client: SqlClient, manifest: LegacyWebhookManifest, hash: String) =
        saveInternalMetadata(client, "legacy-webhook-manifest-complete:${manifest.channelId}", hash, manifest.json())

    private suspend fun saveInternalMetadata(client: SqlClient, key: String, hash: String, response: JsonObject) {
        client.preparedQuery(
            """
            INSERT INTO tb_webhook_operations (actor_id, idempotency_key, request_hash, response, secret_ciphertext)
            VALUES (0, ${'$'}1, ${'$'}2, ${'$'}3::jsonb, NULL)
            ON CONFLICT (actor_id, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash,
                response = EXCLUDED.response, secret_ciphertext = NULL
            """.trimIndent(),
        ).execute(Tuple.of(key, hash, response)).coAwait()
    }

    suspend fun snapshotPage(client: SqlClient, afterId: Long, limit: Int): List<Webhook> = client.preparedQuery(
        "SELECT * FROM tb_webhooks WHERE id > \$1 ORDER BY id LIMIT \$2 FOR SHARE",
    ).execute(Tuple.of(afterId, limit)).coAwait().map { it.webhook() }

    suspend fun findOperation(client: SqlClient, actorId: Long, key: String): WebhookOperation? = client.preparedQuery(
        "SELECT request_hash, response, secret_ciphertext, created_at FROM tb_webhook_operations " +
            "WHERE actor_id = \$1 AND idempotency_key = \$2",
    ).execute(Tuple.of(actorId, key)).coAwait().firstOrNull()?.let {
        WebhookOperation(it.getString("request_hash"), it.getJsonObject("response"), it.getString("secret_ciphertext"),
            it.getOffsetDateTime("created_at").toInstant())
    }

    suspend fun clearOperationSecret(client: SqlClient, actorId: Long, key: String) {
        client.preparedQuery(
            "UPDATE tb_webhook_operations SET secret_ciphertext = NULL " +
                "WHERE actor_id = ${'$'}1 AND actor_id > 0 AND idempotency_key = ${'$'}2",
        ).execute(Tuple.of(actorId, key)).coAwait()
    }

    suspend fun clearExpiredOperationSecrets(client: SqlClient, cutoff: Instant) {
        // Retain idempotency outcomes and actor=0 migration metadata; only response secrets expire.
        client.preparedQuery(
            "UPDATE tb_webhook_operations SET secret_ciphertext = NULL " +
                "WHERE actor_id > 0 AND secret_ciphertext IS NOT NULL AND created_at <= ${'$'}1",
        ).execute(Tuple.of(cutoff.atOffset(ZoneOffset.UTC))).coAwait()
    }

    suspend fun saveOperation(client: SqlClient, actorId: Long, key: String, requestHash: String,
        response: JsonObject, secretCiphertext: String?) {
        client.preparedQuery(
            """
            INSERT INTO tb_webhook_operations (actor_id, idempotency_key, request_hash, response, secret_ciphertext)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4::jsonb, ${'$'}5)
            """.trimIndent(),
        ).execute(Tuple.of(actorId, key, requestHash, response, secretCiphertext)).coAwait()
    }

    private fun Row.webhook() = Webhook(
        id = getLong("id"), channelId = getLong("channel_id"), name = getString("name"),
        avatarUrl = getString("avatar_url"), isSecure = getBoolean("is_secure"), secretHash = getString("secret_hash"),
        isActive = getBoolean("is_active"), deleted = getBoolean("deleted"), createdBy = getLong("created_by"),
        createdAt = getOffsetDateTime("created_at").toInstant(), updatedAt = getOffsetDateTime("updated_at").toInstant(),
        modeChangedAt = getOffsetDateTime("mode_changed_at")?.toInstant(),
    )
}
