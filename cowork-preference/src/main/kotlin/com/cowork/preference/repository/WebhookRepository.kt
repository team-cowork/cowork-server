package com.cowork.preference.repository

import com.cowork.preference.domain.Webhook
import com.cowork.preference.domain.WebhookRequestException
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

    suspend fun create(
        client: SqlClient,
        channelId: Long,
        actorId: Long,
        input: JsonObject,
        secretHash: String?,
    ): Webhook {
        val id = client.query("SELECT nextval(pg_get_serial_sequence('tb_webhooks', 'id')) AS id")
            .execute().coAwait().first().getLong("id")
        if (id !in 1..9_007_199_254_740_991L) throw WebhookRequestException(503, "웹훅 식별자를 발급할 수 없습니다.")
        val row = client.preparedQuery(
            """
            INSERT INTO tb_webhooks (channel_id, name, avatar_url, is_secure, secret_hash, is_active, created_by, id)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8) RETURNING *
            """.trimIndent(),
        ).execute(
            Tuple.of(
                channelId,
                input.getString("name"),
                input.getString("avatarUrl"),
                input.getBoolean("isSecure"),
                secretHash,
                input.getBoolean("isActive"),
                actorId,
                id,
            ),
        ).coAwait().first()
        return row.webhook()
    }

    suspend fun patch(client: SqlClient, webhook: Webhook, input: JsonObject): Webhook = client.preparedQuery(
        """
        UPDATE tb_webhooks SET name = ${'$'}2, avatar_url = ${'$'}3, is_active = ${'$'}4,
            updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
        WHERE id = ${'$'}1 RETURNING *
        """.trimIndent(),
    ).execute(
        Tuple.of(
            webhook.id,
            input.getString("name", webhook.name),
            if (input.containsKey("avatarUrl")) input.getString("avatarUrl") else webhook.avatarUrl,
            input.getBoolean("isActive", webhook.isActive),
        ),
    ).coAwait().first().webhook()

    suspend fun changeMode(
        client: SqlClient,
        webhook: Webhook,
        target: Boolean,
        secretHash: String?,
        now: Instant,
    ): Webhook = client.preparedQuery(
        """
            UPDATE tb_webhooks SET is_secure = ${'$'}2, secret_hash = ${'$'}3, mode_changed_at = ${'$'}4,
                updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
            WHERE id = ${'$'}1 RETURNING *
        """.trimIndent(),
    ).execute(Tuple.of(webhook.id, target, secretHash, now.atOffset(ZoneOffset.UTC))).coAwait().first().webhook()

    suspend fun rotateExecutionId(client: SqlClient, webhookId: Long): Webhook = client.preparedQuery(
        """
        UPDATE tb_webhooks SET execution_id = gen_random_uuid(),
            updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
        WHERE id = ${'$'}1 RETURNING *
        """.trimIndent(),
    ).execute(Tuple.of(webhookId)).coAwait().first().webhook()

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

    suspend fun snapshotPage(client: SqlClient, afterId: Long, limit: Int): List<Webhook> = client.preparedQuery(
        "SELECT * FROM tb_webhooks WHERE id > \$1 ORDER BY id LIMIT \$2 FOR SHARE",
    ).execute(Tuple.of(afterId, limit)).coAwait().map { it.webhook() }

    suspend fun findOperation(client: SqlClient, actorId: Long, key: String): WebhookOperation? = client.preparedQuery(
        "SELECT request_hash, response, secret_ciphertext, created_at FROM tb_webhook_operations " +
            "WHERE actor_id = \$1 AND idempotency_key = \$2",
    ).execute(Tuple.of(actorId, key)).coAwait().firstOrNull()?.let {
        WebhookOperation(
            it.getString("request_hash"),
            it.getJsonObject("response"),
            it.getString("secret_ciphertext"),
            it.getOffsetDateTime("created_at").toInstant(),
        )
    }

    suspend fun clearOperationSecret(client: SqlClient, actorId: Long, key: String) {
        client.preparedQuery(
            "UPDATE tb_webhook_operations SET secret_ciphertext = NULL " +
                "WHERE actor_id = ${'$'}1 AND idempotency_key = ${'$'}2",
        ).execute(Tuple.of(actorId, key)).coAwait()
    }

    suspend fun clearExpiredOperationSecrets(client: SqlClient, cutoff: Instant) {
        // Retain idempotency outcomes; only response secrets expire.
        client.preparedQuery(
            "UPDATE tb_webhook_operations SET secret_ciphertext = NULL " +
                "WHERE secret_ciphertext IS NOT NULL AND created_at <= ${'$'}1",
        ).execute(Tuple.of(cutoff.atOffset(ZoneOffset.UTC))).coAwait()
    }

    suspend fun saveOperation(
        client: SqlClient,
        actorId: Long,
        key: String,
        requestHash: String,
        response: JsonObject,
        secretCiphertext: String?,
    ) {
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
        createdAt = getOffsetDateTime(
            "created_at",
        ).toInstant(),
        updatedAt = getOffsetDateTime("updated_at").toInstant(),
        modeChangedAt = getOffsetDateTime("mode_changed_at")?.toInstant(), executionId = getUUID("execution_id"),
    )
}
