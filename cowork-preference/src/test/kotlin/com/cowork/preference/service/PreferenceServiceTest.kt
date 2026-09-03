package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.PreferenceSettingsUpdate
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PreferenceServiceTest {

    @Test
    fun `GitHub repository read fills label default and drops unknown settings`() = runBlocking {
        val repository = mockk<PreferenceRepository>()
        val cache = mockk<PreferenceCache>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val cached = JsonObject().put("legacy_setting", true)
        coEvery { cache.getSettings(ResourceType.GITHUB_REPO, 77) } returns cached

        val result = PreferenceService(repository, cache, outboxRepository)
            .getSettings(ResourceType.GITHUB_REPO, 77)

        assertEquals(JsonObject().put("label_auto_apply", true), result)
    }

    @Test
    fun `manual account status mutation and action event share one transaction`() = runBlocking {
        val repository = mockk<PreferenceRepository>()
        val cache = mockk<PreferenceCache>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val event = slot<PreferenceEvent>()
        val requested = JsonObject().put("status", "DO_NOT_DISTURB")
        val updatedAt = Instant.parse("2026-08-26T01:02:03Z")

        coEvery { outboxRepository.inTransaction<JsonObject>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> JsonObject>().invoke(connection)
        }
        coEvery {
            repository.upsertSettings(connection, 19, ResourceType.ACCOUNT, requested, true)
        } returns PreferenceSettingsUpdate(requested, "ONLINE", updatedAt, updatedAt)
        coEvery { outboxRepository.enqueue(connection, capture(event)) } returns Unit
        coEvery { cache.setSettings(ResourceType.ACCOUNT, 19, requested) } returns Unit

        val result = PreferenceService(repository, cache, outboxRepository)
            .updateSettings(ResourceType.ACCOUNT, 19, requested)

        assertTrue(result.isSuccess)
        assertEquals("preference.status.changed", event.captured.topic)
        assertEquals("ONLINE", event.captured.payload.getString("previousStatus"))
        assertEquals("DO_NOT_DISTURB", event.captured.payload.getString("newStatus"))
        assertEquals(updatedAt, event.captured.occurredAt)
        coVerifyOrder {
            repository.upsertSettings(connection, 19, ResourceType.ACCOUNT, requested, true)
            outboxRepository.enqueue(connection, any())
            cache.setSettings(ResourceType.ACCOUNT, 19, requested)
        }
    }
}
