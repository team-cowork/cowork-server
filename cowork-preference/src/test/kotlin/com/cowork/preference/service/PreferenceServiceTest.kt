package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.PreferenceSettingsUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
