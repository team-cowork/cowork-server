package com.cowork.preference.service

import com.cowork.preference.domain.ChannelNotificationPreference
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class NotificationServiceTest {

    @Test
    fun `notification read uses the authoritative repository value`() = runBlocking {
        val repository = mockk<NotificationRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val persisted = JsonObject().put("notification", false)
        coEvery { repository.findNotification(11, 22) } returns persisted

        val result = NotificationService(repository, outboxRepository)
            .getNotification(11, 22)

        assertEquals(persisted, result)
    }

    @Test
    fun `notification mutation and state event use the same transaction connection`() = runBlocking {
        val repository = mockk<NotificationRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val persisted = ChannelNotificationPreference(
            accountId = 11,
            channelId = 22,
            notification = false,
            stateOccurredAt = OffsetDateTime.parse("2026-08-26T01:02:03Z"),
        )
        val event = slot<PreferenceEvent>()
        val settings = JsonObject().put("notification", false)

        coEvery { outboxRepository.inTransaction<ChannelNotificationPreference>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> ChannelNotificationPreference>().invoke(connection)
        }
        coEvery { repository.upsertNotification(connection, 11, 22, settings) } returns persisted
        coEvery { outboxRepository.enqueue(connection, capture(event)) } returns Unit

        val result = NotificationService(repository, outboxRepository)
            .updateNotification(11, 22, settings)

        assertEquals(settings, result)
        assertEquals("preference.channel-notification.changed", event.captured.topic)
        assertEquals("11:22", event.captured.key)
        assertEquals(persisted.stateOccurredAt.toInstant(), event.captured.occurredAt)
        coVerifyOrder {
            repository.upsertNotification(connection, 11, 22, settings)
            outboxRepository.enqueue(connection, any())
        }
    }
}
