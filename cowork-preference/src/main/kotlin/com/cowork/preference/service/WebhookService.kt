package com.cowork.preference.service

import com.cowork.preference.domain.Webhook
import com.cowork.preference.domain.WebhookInput
import com.cowork.preference.domain.WebhookModePolicy
import com.cowork.preference.domain.WebhookRequestException
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.WebhookRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import java.time.Duration
import java.time.Instant

class WebhookService(
    private val pool: Pool,
    private val repository: WebhookRepository,
    private val channelRepository: ChannelLifecycleProjectionRepository,
    private val accessGuard: ChannelAccessGuard,
    private val outbox: PreferenceOutboxRepository,
    private val readiness: ProjectionReadinessView,
    private val secrets: WebhookSecrets,
    private val urlSigner: WebhookUrlSigner,
) {
    suspend fun list(channelId: Long, requester: RequesterContext): JsonArray {
        authorize(channelId, requester)
        urlSigner.requireReady()
        requireTextChannel(pool, channelId, lock = false)
        return JsonArray(repository.list(pool, channelId).map { withExecutionPath(it.response()) })
    }

    suspend fun create(channelId: Long, requester: RequesterContext, raw: JsonObject, idempotencyKey: String?): JsonObject {
        val input = WebhookInput.create(raw)
        val key = WebhookInput.idempotencyKey(idempotencyKey)
        authorize(channelId, requester)
        return outbox.inTransaction { connection ->
            authorize(channelId, requester, connection)
            urlSigner.requireReady()
            requireTextChannel(connection, channelId)
            idempotent(connection, requester.userId, key, "CREATE:$channelId:${input.encode()}") {
                val token = if (input.getBoolean("isSecure")) secrets.generate() else null
                val webhook = repository.create(connection, channelId, requester.userId, input, token?.let(WebhookSecrets::hash))
                outbox.enqueue(connection, PreferenceEvents.webhookState(webhook, snapshot = false))
                webhook.response(token)
            }.let(::withExecutionPath)
        }
    }

    suspend fun patch(channelId: Long, webhookId: Long, requester: RequesterContext, raw: JsonObject): JsonObject {
        val input = WebhookInput.patch(raw)
        authorize(channelId, requester)
        return outbox.inTransaction { connection ->
            authorize(channelId, requester, connection)
            urlSigner.requireReady()
            requireTextChannel(connection, channelId)
            val current = requireWebhook(connection, channelId, webhookId)
            if (input.isEmpty) return@inTransaction withExecutionPath(current.response())
            val updated = repository.patch(connection, current, input)
            outbox.enqueue(connection, PreferenceEvents.webhookState(updated, snapshot = false))
            withExecutionPath(updated.response())
        }
    }

    suspend fun changeMode(channelId: Long, webhookId: Long, requester: RequesterContext,
        raw: JsonObject, idempotencyKey: String?): JsonObject {
        val target = WebhookInput.securityMode(raw)
        val key = WebhookInput.idempotencyKey(idempotencyKey)
        authorize(channelId, requester)
        val response = outbox.inTransaction { connection ->
            authorize(channelId, requester, connection)
            urlSigner.requireReady()
            requireTextChannel(connection, channelId)
            idempotent(connection, requester.userId, key, "MODE:$channelId:$webhookId:$target") {
                val current = requireWebhook(connection, channelId, webhookId)
                if (current.isSecure == target) return@idempotent current.response()
                val now = repository.now(connection)
                val retryAfter = WebhookModePolicy.retryAfterSeconds(current, target, now)
                if (retryAfter > 0) {
                    throw WebhookRequestException(429, "웹훅 보안 모드는 변경 후 30초가 지나야 다시 변경할 수 있습니다.", retryAfter, current.modeChangedAt?.plusSeconds(WebhookModePolicy.COOLDOWN_SECONDS))
                }
                val token = if (target) secrets.generate() else null
                val updated = repository.changeMode(connection, current, target, token?.let(WebhookSecrets::hash), now)
                outbox.enqueue(connection, PreferenceEvents.webhookState(updated, snapshot = false))
                updated.response(token)
            }.let(::withExecutionPath)
        }
        response.getJsonObject("operationFailure")?.let { failure ->
            val nextAllowedAt = failure.getString("nextAllowedAt")?.let(Instant::parse)
            val retryAfter = nextAllowedAt?.let {
                val remaining = Duration.between(Instant.now(), it)
                if (remaining.isNegative || remaining.isZero) 0 else remaining.seconds + if (remaining.nano > 0) 1 else 0
            }
            throw WebhookRequestException(failure.getInteger("status"), failure.getString("message"), retryAfter, nextAllowedAt)
        }
        return response
    }

    suspend fun rotateExecutionId(channelId: Long, webhookId: Long, requester: RequesterContext,
        idempotencyKey: String?): JsonObject {
        val key = WebhookInput.idempotencyKey(idempotencyKey)
        authorize(channelId, requester)
        return outbox.inTransaction { connection ->
            authorize(channelId, requester, connection)
            urlSigner.requireReady()
            requireTextChannel(connection, channelId)
            idempotent(connection, requester.userId, key, "ROTATE_EXECUTION_ID:$channelId:$webhookId") {
                val current = requireWebhook(connection, channelId, webhookId)
                val updated = repository.rotateExecutionId(connection, current.id)
                outbox.enqueue(connection, PreferenceEvents.webhookState(updated, snapshot = false))
                updated.response()
            }.let(::withExecutionPath)
        }
    }

    suspend fun delete(channelId: Long, webhookId: Long, requester: RequesterContext) {
        authorize(channelId, requester)
        outbox.inTransaction { connection ->
            authorize(channelId, requester, connection)
            requireTextChannel(connection, channelId)
            requireWebhook(connection, channelId, webhookId)
            val deleted = repository.delete(connection, webhookId)
            outbox.enqueue(connection, PreferenceEvents.webhookState(deleted, snapshot = false))
        }
    }

    suspend fun clearExpiredReplaySecrets() {
        repository.clearExpiredOperationSecrets(pool, repository.now(pool).minusSeconds(SECRET_REPLAY_SECONDS))
    }

    private fun withExecutionPath(response: JsonObject): JsonObject {
        if (response.containsKey("operationFailure")) return response
        return response.copy().put("executionPath", urlSigner.executionPath(response.getString("executionId")))
    }

    private suspend fun authorize(channelId: Long, requester: RequesterContext, client: SqlClient = pool) {
        if (!readiness.isReady) throw ProjectionNotReadyException("webhook ownership migration or channel projection is not ready")
        accessGuard.requireSettingsManager(client, channelId, requester)
    }

    private suspend fun requireTextChannel(client: SqlClient, channelId: Long, lock: Boolean = true) {
        val channel = if (lock) channelRepository.findForUpdate(client, channelId) else channelRepository.findForRead(client, channelId)
        if (channel == null || channel.deleted) throw WebhookRequestException(404, "채널을 찾을 수 없습니다.")
        if (channel.channelType != "TEXT") throw WebhookRequestException(400, "TEXT 채널에서만 웹훅을 사용할 수 있습니다.")
    }

    private suspend fun requireWebhook(connection: SqlConnection, channelId: Long, webhookId: Long): Webhook =
        repository.findForUpdate(connection, webhookId)?.takeIf { it.channelId == channelId && !it.deleted }
            ?: throw WebhookRequestException(404, "웹훅을 찾을 수 없습니다.")

    private suspend fun idempotent(connection: SqlConnection, actorId: Long, key: String, request: String,
        action: suspend () -> JsonObject): JsonObject {
        val hash = WebhookSecrets.hash(request)
        val context = "$actorId:$key:$hash"
        repository.findOperation(connection, actorId, key)?.let { saved ->
            if (saved.requestHash != hash) throw WebhookRequestException(409, "Idempotency-Key를 다른 요청에 재사용할 수 없습니다.")
            val response = saved.response.copy()
            response.getLong("id")?.let { id ->
                val webhook = repository.findForUpdate(connection, id)
                val executionId = response.getString("executionId")
                if (webhook == null || webhook.deleted || webhook.channelId != response.getLong("channelId") ||
                    executionId != null && executionId != webhook.requireExecutionId().toString()) {
                    throw WebhookRequestException(409, "웹훅 실행 ID가 변경되었거나 삭제되어 이전 요청을 재생할 수 없습니다.")
                }
                response.put("executionId", webhook.requireExecutionId().toString())
            }
            val token = saved.secretCiphertext?.let {
                if (repository.now(connection) >= saved.createdAt.plusSeconds(SECRET_REPLAY_SECONDS)) {
                    repository.clearOperationSecret(connection, actorId, key)
                    null
                } else {
                    secrets.decrypt(it, context)
                }
            }
            return response.put("token", token)
        }
        val response = try {
            action()
        } catch (error: WebhookRequestException) {
            if (error.status != 429) throw error
            JsonObject().put("operationFailure", JsonObject()
                .put("status", error.status).put("message", error.message)
                .put("nextAllowedAt", error.nextAllowedAt?.toString()))
        }
        val encryptedToken = response.getString("token")?.let { secrets.encrypt(it, context) }
        repository.saveOperation(connection, actorId, key, hash, response.copy().put("token", null), encryptedToken)
        return response
    }

    companion object {
        private const val SECRET_REPLAY_SECONDS = 24 * 60 * 60L
    }
}
