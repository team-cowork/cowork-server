package com.cowork.preference.service

import com.cowork.preference.domain.ChannelNotificationPreference
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class NotificationServiceTest {

    private lateinit var repository: NotificationRepository
    private lateinit var outboxRepository: PreferenceOutboxRepository
    private lateinit var service: NotificationService

    @BeforeEach
    fun setUp() {
        repository = mockk()
        outboxRepository = mockk(relaxed = true)
        service = NotificationService(repository, outboxRepository)
    }

    @Nested
    inner class GetNotification {

        @Test
        fun `저장된 채널 설정이 있으면 저장된 알림 값을 반환한다`() = runBlocking {
            val persisted = JsonObject().put("notification", false)
            coEvery { repository.findNotification(11L, 22L) } returns persisted

            assertEquals(persisted, service.getNotification(11L, 22L))
        }

        @Test
        fun `저장된 채널 설정이 없으면 알림을 켠 기본값을 반환한다`() = runBlocking {
            coEvery { repository.findNotification(11L, 22L) } returns null

            assertEquals(
                JsonObject().put("notification", true),
                service.getNotification(11L, 22L),
            )
        }
    }

    @Nested
    inner class UpdateNotification {

        @Test
        fun `알림 값을 끄면 정규화된 설정을 저장하고 반환한다`() = runBlocking {
            val connection = mockk<SqlConnection>()
            val requested = JsonObject().put("notification", false).put("unknown", true)
            val normalized = JsonObject().put("notification", false)
            coEvery { outboxRepository.inTransaction<ChannelNotificationPreference>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> ChannelNotificationPreference>().invoke(connection)
            }
            coEvery { repository.upsertNotification(connection, 11L, 22L, normalized) } returns
                ChannelNotificationPreference(
                    accountId = 11L,
                    channelId = 22L,
                    notification = false,
                    stateOccurredAt = OffsetDateTime.parse("2026-08-30T01:02:03Z"),
                )

            val result = service.updateNotification(11L, 22L, requested)

            assertEquals(normalized, result)
        }
    }
}
