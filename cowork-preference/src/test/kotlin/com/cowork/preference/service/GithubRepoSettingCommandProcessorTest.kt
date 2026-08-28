package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.messaging.GithubRepoSettingCommand
import com.cowork.preference.messaging.GithubRepoSettingCommandEnvelope
import com.cowork.preference.messaging.GithubRepoSettingCommandQuarantineRecord
import com.cowork.preference.messaging.GithubRepoSettingCommandType
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.PreferenceSettingsUpdate
import com.cowork.preference.repository.ProcessedGithubRepoSettingCommand
import com.cowork.preference.repository.ProcessedGithubRepoSettingCommandMatches
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class GithubRepoSettingCommandProcessorTest {

    @Test
    fun `UPDATE는 tombstone-aware mutation과 state result inbox를 원자적으로 적재한 뒤 cache를 갱신한다`() = runBlocking {
        val fixture = Fixture()
        val stateVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        val settings = JsonObject().put("label_auto_apply", false)
        coEvery { fixture.inboxRepository.findMatches(fixture.connection, COMMAND) } returns noGithubCommandMatches()
        coEvery {
            fixture.preferenceRepository.upsertGithubRepoSettings(
                fixture.connection,
                COMMAND.repoId,
                settings,
            )
        } returns PreferenceSettingsUpdate(settings, null, stateVersion, stateVersion)
        val events = mutableListOf<PreferenceEvent>()
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, any()) } coAnswers {
            events += secondArg<PreferenceEvent>()
        }
        val storedResult = slot<JsonObject>()
        coEvery {
            fixture.inboxRepository.insert(fixture.connection, COMMAND, capture(storedResult))
        } returns Unit
        coEvery {
            fixture.cache.setSettings(ResourceType.GITHUB_REPO, COMMAND.repoId, settings)
        } returns Unit

        fixture.processor.process(COMMAND)

        assertEquals(
            listOf("preference.github-repo.setting.state", "preference.github-repo.setting.result"),
            events.map(PreferenceEvent::topic),
        )
        assertEquals(COMMAND.repoId.toString(), events.first().key)
        assertEquals(false, events.first().payload.getJsonObject("settings").getBoolean("label_auto_apply"))
        assertEquals(stateVersion.toString(), storedResult.captured.getString("stateOccurredAt"))
        assertEquals(COMMAND.idempotencyKey, storedResult.captured.getString("idempotencyKey"))
        coVerifyOrder {
            fixture.preferenceRepository.upsertGithubRepoSettings(
                fixture.connection,
                COMMAND.repoId,
                settings,
            )
            fixture.outboxRepository.enqueue(fixture.connection, any())
            fixture.outboxRepository.enqueue(fixture.connection, any())
            fixture.inboxRepository.insert(fixture.connection, COMMAND, any())
            fixture.cache.setSettings(ResourceType.GITHUB_REPO, COMMAND.repoId, settings)
        }
    }

    @Test
    fun `같은 operationId의 설정 값 또는 requester 변조는 failed result로 종료한다`() = runBlocking {
        val fixture = Fixture()
        val storedResult = JsonObject()
            .put("occurredAt", "2026-08-27T01:02:03.123456Z")
            .put("settings", JsonObject().put("label_auto_apply", true))
        val existing = ProcessedGithubRepoSettingCommand(
            operationId = COMMAND.operationId,
            idempotencyKey = COMMAND.idempotencyKey,
            commandType = GithubRepoSettingCommandType.UPDATE,
            repoId = COMMAND.repoId,
            requestedBy = 8L,
            labelAutoApply = true,
            commandOccurredAt = COMMAND.occurredAt,
            result = storedResult,
        )
        coEvery { fixture.inboxRepository.findMatches(fixture.connection, COMMAND) } returns
            matchesByBoth(existing)
        val resultEvent = slot<PreferenceEvent>()
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, capture(resultEvent)) } returns Unit

        fixture.processor.process(COMMAND)

        assertEquals("FAILED", resultEvent.captured.payload.getString("status"))
        assertEquals("OPERATION_ID_REUSED", resultEvent.captured.payload.getJsonObject("error").getString("code"))
        coVerify(exactly = 0) { fixture.preferenceRepository.upsertGithubRepoSettings(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.inboxRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `DELETE는 설정을 삭제하고 tombstone과 inbox만 원자적으로 적재한 뒤 cache를 무효화한다`() = runBlocking {
        val fixture = Fixture()
        val stateVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        coEvery { fixture.inboxRepository.findMatches(fixture.connection, DELETE_COMMAND) } returns
            noGithubCommandMatches()
        coEvery {
            fixture.preferenceRepository.deleteGithubRepoSettings(fixture.connection, DELETE_COMMAND.repoId)
        } returns stateVersion
        val events = mutableListOf<PreferenceEvent>()
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, any()) } coAnswers {
            events += secondArg<PreferenceEvent>()
        }
        coEvery { fixture.inboxRepository.insert(fixture.connection, DELETE_COMMAND, null) } returns Unit
        coEvery {
            fixture.cache.invalidateSettings(ResourceType.GITHUB_REPO, DELETE_COMMAND.repoId)
        } returns Unit

        fixture.processor.process(DELETE_COMMAND)

        assertEquals(listOf("preference.github-repo.setting.state"), events.map(PreferenceEvent::topic))
        assertEquals("DELETE", events.single().payload.getString("eventType"))
        assertEquals(null, events.single().payload.getValue("settings"))
        assertEquals(stateVersion.toString(), events.single().payload.getString("occurredAt"))
        coVerifyOrder {
            fixture.preferenceRepository.deleteGithubRepoSettings(fixture.connection, DELETE_COMMAND.repoId)
            fixture.outboxRepository.enqueue(fixture.connection, any())
            fixture.inboxRepository.insert(fixture.connection, DELETE_COMMAND, null)
            fixture.cache.invalidateSettings(ResourceType.GITHUB_REPO, DELETE_COMMAND.repoId)
        }
    }

    @Test
    fun `동일한 DELETE 재처리는 mutation과 event 없이 cache만 무효화한다`() = runBlocking {
        val fixture = Fixture()
        val existing = ProcessedGithubRepoSettingCommand(
            operationId = DELETE_COMMAND.operationId,
            idempotencyKey = DELETE_COMMAND.idempotencyKey,
            commandType = GithubRepoSettingCommandType.DELETE,
            repoId = DELETE_COMMAND.repoId,
            requestedBy = DELETE_COMMAND.requestedBy,
            labelAutoApply = null,
            commandOccurredAt = DELETE_COMMAND.occurredAt,
            result = null,
        )
        coEvery { fixture.inboxRepository.findMatches(fixture.connection, DELETE_COMMAND) } returns
            matchesByBoth(existing)
        coEvery {
            fixture.cache.invalidateSettings(ResourceType.GITHUB_REPO, DELETE_COMMAND.repoId)
        } returns Unit

        fixture.processor.process(DELETE_COMMAND)

        coVerify(exactly = 1) {
            fixture.cache.invalidateSettings(ResourceType.GITHUB_REPO, DELETE_COMMAND.repoId)
        }
        coVerify(exactly = 0) {
            fixture.preferenceRepository.deleteGithubRepoSettings(any(), any())
            fixture.outboxRepository.enqueue(any(), any())
            fixture.inboxRepository.insert(any(), any(), any())
        }
    }

    @Test
    fun `같은 requester와 idempotency key의 동일 요청은 새 operationId에도 mutation 없이 결과를 재생한다`() = runBlocking {
        val fixture = Fixture()
        val replayCommand = COMMAND.copy(operationId = "e8b784d4-24ca-45aa-8aab-1ae2a2bc6a6a")
        val settings = JsonObject().put("label_auto_apply", false)
        val storedResult = JsonObject()
            .put("operationId", COMMAND.operationId)
            .put("status", "SUCCEEDED")
            .put("settings", settings)
            .put("occurredAt", "2026-08-27T01:02:03.123456Z")
        val existing = processedCommand(COMMAND, storedResult)
        coEvery { fixture.inboxRepository.findMatches(fixture.connection, replayCommand) } returns
            ProcessedGithubRepoSettingCommandMatches(null, existing)
        val resultEvent = slot<PreferenceEvent>()
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, capture(resultEvent)) } returns Unit
        coEvery {
            fixture.cache.invalidateSettings(ResourceType.GITHUB_REPO, replayCommand.repoId)
        } returns Unit

        fixture.processor.process(replayCommand)

        assertEquals(replayCommand.operationId, resultEvent.captured.key)
        assertEquals(replayCommand.operationId, resultEvent.captured.payload.getString("operationId"))
        assertEquals(COMMAND.operationId, storedResult.getString("operationId"))
        coVerify(exactly = 1) {
            fixture.cache.invalidateSettings(ResourceType.GITHUB_REPO, replayCommand.repoId)
        }
        coVerify(exactly = 0) { fixture.preferenceRepository.upsertGithubRepoSettings(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.inboxRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `같은 requester와 idempotency key의 다른 요청은 mutation 없이 failed result로 종료한다`() = runBlocking {
        val fixture = Fixture()
        val conflictingCommand = COMMAND.copy(
            operationId = "e8b784d4-24ca-45aa-8aab-1ae2a2bc6a6a",
            labelAutoApply = true,
        )
        val existing = processedCommand(
            COMMAND,
            JsonObject()
                .put("settings", JsonObject().put("label_auto_apply", false))
                .put("occurredAt", "2026-08-27T01:02:03.123456Z"),
        )
        coEvery { fixture.inboxRepository.findMatches(fixture.connection, conflictingCommand) } returns
            ProcessedGithubRepoSettingCommandMatches(null, existing)
        val resultEvent = slot<PreferenceEvent>()
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, capture(resultEvent)) } returns Unit

        fixture.processor.process(conflictingCommand)

        assertEquals("FAILED", resultEvent.captured.payload.getString("status"))
        assertEquals(
            "IDEMPOTENCY_KEY_REUSED",
            resultEvent.captured.payload.getJsonObject("error").getString("code"),
        )
        coVerify(exactly = 0) { fixture.preferenceRepository.upsertGithubRepoSettings(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.inboxRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `상관 가능한 malformed command는 quarantine과 failed result를 한 transaction에 적재한다`() = runBlocking {
        val fixture = Fixture()
        val envelope = GithubRepoSettingCommandEnvelope(COMMAND.operationId, COMMAND.idempotencyKey, COMMAND.repoId)
        val quarantine = GithubRepoSettingCommandQuarantineRecord(
            topic = "preference.github-repo.setting.command",
            partition = 1,
            offset = 21,
            key = COMMAND.repoId.toString(),
            payload = "{}",
            reason = "settings is invalid",
        )
        coEvery { fixture.inboxRepository.quarantine(fixture.connection, quarantine) } returns true
        val resultEvent = slot<PreferenceEvent>()
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, capture(resultEvent)) } returns Unit

        fixture.processor.rejectInvalid(envelope, quarantine, quarantine.reason)

        assertEquals("FAILED", resultEvent.captured.payload.getString("status"))
        assertEquals("INVALID_COMMAND", resultEvent.captured.payload.getJsonObject("error").getString("code"))
        coVerifyOrder {
            fixture.inboxRepository.quarantine(fixture.connection, quarantine)
            fixture.outboxRepository.enqueue(fixture.connection, any())
        }
    }

    private class Fixture {
        val preferenceRepository = mockk<PreferenceRepository>()
        val inboxRepository = mockk<GithubRepoSettingCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val cache = mockk<PreferenceCache>()
        val connection = mockk<SqlConnection>()
        val processor = GithubRepoSettingCommandProcessor(
            preferenceRepository,
            inboxRepository,
            outboxRepository,
            cache,
        )

        init {
            coEvery { outboxRepository.inTransaction<Any>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> Any>().invoke(connection)
            }
        }
    }

    private fun noGithubCommandMatches() = ProcessedGithubRepoSettingCommandMatches(
        byOperationId = null,
        byRequesterAndIdempotencyKey = null,
    )

    private fun matchesByBoth(command: ProcessedGithubRepoSettingCommand) = ProcessedGithubRepoSettingCommandMatches(
        byOperationId = command,
        byRequesterAndIdempotencyKey = command,
    )

    private fun processedCommand(command: GithubRepoSettingCommand, result: JsonObject?) =
        ProcessedGithubRepoSettingCommand(
            operationId = command.operationId,
            idempotencyKey = command.idempotencyKey,
            commandType = command.commandType,
            repoId = command.repoId,
            requestedBy = command.requestedBy,
            labelAutoApply = command.labelAutoApply,
            commandOccurredAt = command.occurredAt,
            result = result,
        )

    private companion object {
        val COMMAND = GithubRepoSettingCommand(
            operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e",
            idempotencyKey = "github-policy-1",
            commandType = GithubRepoSettingCommandType.UPDATE,
            repoId = 101L,
            labelAutoApply = false,
            requestedBy = 7L,
            occurredAt = Instant.parse("2026-08-27T01:01:00Z"),
        )
        val DELETE_COMMAND = GithubRepoSettingCommand(
            operationId = "4488abbb-a79d-3fd4-8f34-11657e5bc045",
            idempotencyKey = "github-repo-setting-delete:101",
            commandType = GithubRepoSettingCommandType.DELETE,
            repoId = 101L,
            labelAutoApply = null,
            requestedBy = null,
            occurredAt = Instant.parse("2026-08-27T02:01:00Z"),
        )
    }
}
