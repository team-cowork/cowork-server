package com.cowork.preference.service

import com.cowork.preference.domain.ChannelNotificationPreference
import com.cowork.preference.domain.NotificationLevel
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
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
        fun `알림 수준이 없는 기존 설정은 notification 값으로 알림 수준을 채워 반환한다`() = runBlocking {
            coEvery { repository.findNotification(11L, 22L) } returns JsonObject().put("notification", false)

            assertEquals(
                settings(notification = false, level = "NONE", mutedUntil = null),
                service.getNotification(11L, 22L),
            )
        }

        @Test
        fun `저장된 채널 설정이 없으면 알림을 켠 기본값을 반환한다`() = runBlocking {
            coEvery { repository.findNotification(11L, 22L) } returns null

            assertEquals(
                settings(notification = true, level = "ALL", mutedUntil = null),
                service.getNotification(11L, 22L),
            )
        }
    }

    @Nested
    inner class UpdateNotification {

        @Test
        fun `알림 값을 끄면 정규화된 설정을 저장하고 반환한다`() = runBlocking {
            val requested = JsonObject().put("notification", false).put("unknown", true)
            val normalized = settings(notification = false, level = "NONE", mutedUntil = null)
            stubUpsert(normalized, NotificationLevel.NONE, null)

            val result = service.updateNotification(11L, 22L, requested)

            assertEquals(normalized, result)
        }

        @Test
        fun `알림 수준과 음소거 시각을 함께 보내면 알림 수준이 notification 값보다 우선한다`() = runBlocking {
            val requested = JsonObject()
                .put("notification", false)
                .put("notification_level", "MENTIONS")
                .put("muted_until", "2026-09-01T00:00:00Z")
            val normalized = settings(notification = true, level = "MENTIONS", mutedUntil = "2026-09-01T00:00:00Z")
            stubUpsert(normalized, NotificationLevel.MENTIONS, Instant.parse("2026-09-01T00:00:00Z"))

            val result = service.updateNotification(11L, 22L, requested)

            assertEquals(normalized, result)
        }

        @Test
        fun `지원하지 않는 알림 수준이면 저장하지 않는다`() {
            val requested = JsonObject().put("notification_level", "SOMETIMES")

            assertThrows<IllegalArgumentException> {
                runBlocking { service.updateNotification(11L, 22L, requested) }
            }
            coVerify(exactly = 0) { repository.upsertNotification(any(), any(), any(), any()) }
        }

        @Test
        fun `음소거 시각이 ISO-8601이 아니면 저장하지 않는다`() {
            val requested = JsonObject().put("muted_until", "tomorrow")

            assertThrows<IllegalArgumentException> {
                runBlocking { service.updateNotification(11L, 22L, requested) }
            }
            coVerify(exactly = 0) { repository.upsertNotification(any(), any(), any(), any()) }
        }

        private fun stubUpsert(normalized: JsonObject, level: NotificationLevel, mutedUntil: Instant?) {
            val connection = mockk<SqlConnection>()
            coEvery { outboxRepository.inTransaction<ChannelNotificationPreference>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> ChannelNotificationPreference>().invoke(connection)
            }
            coEvery { repository.upsertNotification(connection, 11L, 22L, normalized) } returns
                ChannelNotificationPreference(
                    accountId = 11L,
                    channelId = 22L,
                    notificationLevel = level,
                    mutedUntil = mutedUntil,
                    stateOccurredAt = OffsetDateTime.parse("2026-08-30T01:02:03Z"),
                )
        }
    }

    private fun settings(notification: Boolean, level: String, mutedUntil: String?): JsonObject = JsonObject()
        .put("notification", notification)
        .put("notification_level", level)
        .put("muted_until", mutedUntil)
}
