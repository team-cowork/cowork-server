package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.cache.VersionedSettings
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.messaging.GithubRepoSettingCommand
import com.cowork.preference.messaging.GithubRepoSettingCommandEnvelope
import com.cowork.preference.messaging.GithubRepoSettingCommandQuarantineRecord
import com.cowork.preference.messaging.GithubRepoSettingCommandType
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.ProcessedGithubRepoSettingCommand
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import java.time.Instant
import java.time.temporal.ChronoUnit

class GithubRepoSettingCommandProcessor(
    private val preferenceRepository: PreferenceRepository,
    private val inboxRepository: GithubRepoSettingCommandInboxRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val cache: PreferenceCache,
) {
    suspend fun process(command: GithubRepoSettingCommand) {
        val cacheAction = outboxRepository.inTransaction { connection ->
            val matches = inboxRepository.findMatches(connection, command)
            matches.byOperationId?.let { existing ->
                return@inTransaction if (existing.matches(command)) {
                    replay(connection, command.operationId, existing)
                } else {
                    enqueueFailure(
                        connection,
                        command.operationId,
                        command.idempotencyKey,
                        command.repoId,
                        "OPERATION_ID_REUSED",
                        "operationId was already used for a different request",
                    )
                    CacheAction.None
                }
            }
            matches.byRequesterAndIdempotencyKey?.let { existing ->
                return@inTransaction if (existing.matches(command)) {
                    replay(connection, command.operationId, existing)
                } else {
                    enqueueFailure(
                        connection,
                        command.operationId,
                        command.idempotencyKey,
                        command.repoId,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used for a different request",
                    )
                    CacheAction.None
                }
            }

            when (command.commandType) {
                GithubRepoSettingCommandType.UPDATE -> applyUpdate(connection, command)
                GithubRepoSettingCommandType.DELETE -> applyDelete(connection, command)
            }
        }
        when (cacheAction) {
            is CacheAction.Set -> cache.setSettings(ResourceType.GITHUB_REPO, command.repoId, cacheAction.settings)
            CacheAction.None -> Unit
        }
    }

    suspend fun rejectInvalid(
        envelope: GithubRepoSettingCommandEnvelope,
        quarantineRecord: GithubRepoSettingCommandQuarantineRecord,
        reason: String,
    ) {
        outboxRepository.inTransaction { connection ->
            if (!inboxRepository.quarantine(connection, quarantineRecord)) return@inTransaction
            enqueueFailure(
                connection,
                envelope.operationId,
                envelope.idempotencyKey,
                envelope.repoId,
                "INVALID_COMMAND",
                normalizedErrorMessage(reason),
            )
        }
    }

    private suspend fun replay(
        connection: SqlConnection,
        operationId: String,
        existing: ProcessedGithubRepoSettingCommand,
    ): CacheAction = when (existing.commandType) {
        GithubRepoSettingCommandType.UPDATE -> {
            val result = requireNotNull(existing.result) { "UPDATE inbox result is missing" }
                .copy()
                .put("operationId", operationId)
            val occurredAt = Instant.parse(result.getString("occurredAt"))
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.githubRepoSettingResult(operationId, result, occurredAt),
            )
            currentState(connection, existing.repoId)
        }
        GithubRepoSettingCommandType.DELETE -> {
            require(existing.result == null) { "DELETE inbox must not contain a result" }
            currentState(connection, existing.repoId)
        }
    }

    /**
     * 재처리 시 처음 처리 뒤의 캐시 기록이 빠졌을 수 있으므로 현재 DB 상태를 그 version으로 기록한다.
     * 처리된 명령은 설정 행이나 tombstone을 남기므로 상태가 없으면 기록할 값도 없다.
     */
    private suspend fun currentState(connection: SqlConnection, repoId: Long): CacheAction {
        val state = preferenceRepository.findGithubRepoSettingPage(connection, afterRepoId = repoId - 1, limit = 1)
            .singleOrNull()
            ?.takeIf { it.repoId == repoId }
            ?: return CacheAction.None
        return cacheState(state.settings, state.stateOccurredAt)
    }

    /** 삭제된 설정(null)은 조회와 같은 기본값으로 기록해, 삭제 전 값을 늦게 채우는 조회를 version으로 막는다. */
    private fun cacheState(settings: JsonObject?, stateOccurredAt: Instant) = CacheAction.Set(
        VersionedSettings(
            JsonObject().put("label_auto_apply", settings?.getBoolean("label_auto_apply", true) ?: true),
            stateOccurredAt,
        ),
    )

    private suspend fun enqueueFailure(
        connection: SqlConnection,
        operationId: String,
        idempotencyKey: String,
        repoId: Long,
        code: String,
        message: String,
    ) {
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val result = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", operationId)
            .put("idempotencyKey", idempotencyKey)
            .put("repoId", repoId)
            .put("status", "FAILED")
            .put("settings", null)
            .put("error", JsonObject().put("code", code).put("message", normalizedErrorMessage(message)))
            .put("stateOccurredAt", null)
            .put("occurredAt", occurredAt.toString())
        outboxRepository.enqueue(
            connection,
            PreferenceEvents.githubRepoSettingResult(operationId, result, occurredAt),
        )
    }

    private fun ProcessedGithubRepoSettingCommand.matches(command: GithubRepoSettingCommand): Boolean =
        idempotencyKey == command.idempotencyKey &&
            commandType == command.commandType &&
            repoId == command.repoId &&
            requestedBy == command.requestedBy &&
            labelAutoApply == command.labelAutoApply

    private fun normalizedErrorMessage(message: String): String =
        message.ifBlank { "invalid command" }.take(MAX_GITHUB_ERROR_MESSAGE_LENGTH)

    private suspend fun applyUpdate(connection: SqlConnection, command: GithubRepoSettingCommand): CacheAction.Set {
        val labelAutoApply = requireNotNull(command.labelAutoApply) { "UPDATE label_auto_apply is required" }
        val update = preferenceRepository.upsertGithubRepoSettings(
            client = connection,
            repoId = command.repoId,
            settings = JsonObject().put("label_auto_apply", labelAutoApply),
        )
        val normalizedSettings = JsonObject().put(
            "label_auto_apply",
            update.settings.getBoolean("label_auto_apply", true),
        )
        val state = PreferenceEvents.githubRepoSettingState(
            repoId = command.repoId,
            settings = normalizedSettings,
            occurredAt = update.stateOccurredAt,
            snapshot = false,
        )
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val result = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", command.operationId)
            .put("idempotencyKey", command.idempotencyKey)
            .put("repoId", command.repoId)
            .put("status", "SUCCEEDED")
            .put("settings", normalizedSettings)
            .put("error", null)
            .put("stateOccurredAt", update.stateOccurredAt.toString())
            .put("occurredAt", occurredAt.toString())
        outboxRepository.enqueue(connection, state)
        outboxRepository.enqueue(
            connection,
            PreferenceEvents.githubRepoSettingResult(command.operationId, result, occurredAt),
        )
        inboxRepository.insert(connection, command, result)
        return CacheAction.Set(VersionedSettings(normalizedSettings, update.stateOccurredAt))
    }

    private suspend fun applyDelete(connection: SqlConnection, command: GithubRepoSettingCommand): CacheAction {
        require(command.labelAutoApply == null) { "DELETE settings must be absent" }
        val stateOccurredAt = preferenceRepository.deleteGithubRepoSettings(connection, command.repoId)
        outboxRepository.enqueue(
            connection,
            PreferenceEvents.githubRepoSettingState(
                repoId = command.repoId,
                settings = null,
                occurredAt = stateOccurredAt,
                snapshot = false,
            ),
        )
        inboxRepository.insert(connection, command, null)
        return cacheState(null, stateOccurredAt)
    }

    private sealed interface CacheAction {
        data class Set(val settings: VersionedSettings) : CacheAction
        data object None : CacheAction
    }

    private companion object {
        const val MAX_GITHUB_ERROR_MESSAGE_LENGTH = 500
    }
}
