package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.PreferenceSettingsUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class PreferenceServiceTest {

    private lateinit var repository: PreferenceRepository
    private lateinit var cache: PreferenceCache
    private lateinit var outboxRepository: PreferenceOutboxRepository
    private lateinit var service: PreferenceService

    @BeforeEach
    fun setUp() {
        repository = mockk()
        cache = mockk(relaxed = true)
        outboxRepository = mockk(relaxed = true)
        service = PreferenceService(repository, cache, outboxRepository)
    }

    @Test
    fun `일반 텍스트 채널 조회는 보관 중인 구형 webhook 비밀키를 반환하지 않는다`() = runBlocking {
        val stored = JsonObject().put("slowmode_seconds", 10)
            .put("webhook", JsonObject().put("secret_key", "retired-secret").put("is_active", false))
        coEvery { cache.getSettings(ResourceType.TEXT_CHANNEL, 11) } returns stored
        assertEquals(JsonObject().put("slowmode_seconds", 10), service.getSettings(ResourceType.TEXT_CHANNEL, 11))
        assertTrue(stored.containsKey("webhook"))
    }

    @Test
    fun `구형 webhook 설정을 보내면 null이어도 저장하지 않는다`() = runBlocking {
        for (value in listOf(null, JsonObject().put("is_active", true))) {
            val result = service.updateSettings(ResourceType.TEXT_CHANNEL, 11, JsonObject().put("webhook", value))
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        coVerify(exactly = 0) { outboxRepository.inTransaction<JsonObject>(any()) }
    }

    @Nested
    inner class GetSettings {

        @Test
        fun `GitHub 저장소 설정은 알 수 없는 값을 버리고 label 기본값을 채운다`() = runBlocking {
            coEvery { cache.getSettings(ResourceType.GITHUB_REPO, 77L) } returns
                JsonObject().put("legacy_setting", true)

            val result = service.getSettings(ResourceType.GITHUB_REPO, 77L)

            assertEquals(JsonObject().put("label_auto_apply", true), result)
            coVerify(exactly = 0) { repository.findSettings(any(), any()) }
        }
    }

    @Nested
    inner class UpdateSettings {

        @Test
        fun `계정 상태 값이 허용 목록에 없으면 저장하지 않는다`() = runBlocking {
            val result = service.updateSettings(
                ResourceType.ACCOUNT,
                19L,
                JsonObject().put("status", "BUSY"),
            )

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            coVerify(exactly = 0) { outboxRepository.inTransaction<JsonObject>(any()) }
        }

        @Test
        fun `팀 닉네임 형식 적용 여부만 바꾸면 현재 예시와 합쳐 검증한다`() = runBlocking {
            val connection = mockk<SqlConnection>()
            val current = JsonObject().put("nickname_format_example", "2학년 홍길동")
            val requested = JsonObject().put("nickname_format_enforced", true)
            val persisted = current.copy().mergeIn(requested)
            val now = Instant.parse("2026-08-30T01:02:03Z")
            coEvery { cache.getSettings(ResourceType.TEAM, 3L) } returns current
            coEvery { outboxRepository.inTransaction<JsonObject>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> JsonObject>().invoke(connection)
            }
            coEvery {
                repository.upsertSettings(connection, 3L, ResourceType.TEAM, requested, false)
            } returns PreferenceSettingsUpdate(persisted, null, now, now)

            val result = service.updateSettings(ResourceType.TEAM, 3L, requested)

            assertEquals(persisted, result.getOrThrow())
        }

        @Test
        fun `계정 설정을 바꾸면 저장된 전체 설정으로 계정 설정 state를 같은 transaction에 기록한다`() = runBlocking {
            val connection = mockk<SqlConnection>()
            val requested = JsonObject().put("notification_level", "MENTIONS")
            val persisted = JsonObject().put("notification_level", "MENTIONS").put("timezone", "Asia/Seoul")
            val updatedAt = Instant.parse("2026-10-23T01:02:03Z")
            val stateOccurredAt = Instant.parse("2026-10-23T01:02:03.000001Z")
            val event = slot<PreferenceEvent>()
            coEvery { outboxRepository.inTransaction<JsonObject>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> JsonObject>().invoke(connection)
            }
            coEvery {
                repository.upsertSettings(connection, 19L, ResourceType.ACCOUNT, requested, false)
            } returns PreferenceSettingsUpdate(persisted, null, updatedAt, stateOccurredAt)
            coEvery { outboxRepository.enqueue(connection, capture(event)) } returns Unit

            service.updateSettings(ResourceType.ACCOUNT, 19L, requested).getOrThrow()

            assertEquals(PreferenceEvents.ACCOUNT_SETTING_STATE_TOPIC, event.captured.topic)
            assertEquals(stateOccurredAt, event.captured.occurredAt)
            assertEquals("MENTIONS", event.captured.payload.getString("notificationLevel"))
            assertEquals("Asia/Seoul", event.captured.payload.getString("timezone"))
        }

        @Test
        fun `닉네임 형식을 적용하지만 예시가 없으면 저장하지 않는다`() = runBlocking {
            coEvery { cache.getSettings(ResourceType.TEAM, 3L) } returns JsonObject()

            val result = service.updateSettings(
                ResourceType.TEAM,
                3L,
                JsonObject().put("nickname_format_enforced", true),
            )

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            coVerify(exactly = 0) { outboxRepository.inTransaction<JsonObject>(any()) }
        }
    }
}
