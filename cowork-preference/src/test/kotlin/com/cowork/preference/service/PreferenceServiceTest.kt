package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.cache.VersionedSettings
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.messaging.GithubRepoSettingCommand
import com.cowork.preference.messaging.GithubRepoSettingCommandType
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.PreferenceSettingsUpdate
import com.cowork.preference.repository.ProcessedGithubRepoSettingCommandMatches
import com.cowork.preference.repository.ResourceSettingState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
        coVerify(exactly = 0) { outboxRepository.inTransaction<VersionedSettings>(any()) }
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
    inner class CacheFreshness {

        private val connection = mockk<SqlConnection>()
        private val cached = mutableMapOf<Long, VersionedSettings>()
        private val olderVersion = Instant.parse("2026-10-28T01:00:00Z")
        private val newerVersion = Instant.parse("2026-10-28T01:00:00.000001Z")
        private val older = JsonObject().put("tag_spam_block", false)
        private val newer = JsonObject().put("tag_spam_block", true)

        /** PreferenceCache의 계약대로 캐시에 있는 version보다 새로운 기록만 남긴다. 테스트마다 resource id가 다르다. */
        @BeforeEach
        fun setUpCache() {
            coEvery { cache.getSettings(any(), any()) } answers { cached[secondArg()]?.settings }
            coEvery { cache.populateSettings(any(), any(), any()) } answers { keepNewest(secondArg(), thirdArg()) }
            coEvery { cache.setSettings(any(), any(), any()) } answers { keepNewest(secondArg(), thirdArg()) }
            coEvery { cache.populateSettingsBulk(any(), any()) } answers {
                secondArg<Map<Long, VersionedSettings>>().forEach { (id, settings) -> keepNewest(id, settings) }
            }
            coEvery { outboxRepository.inTransaction<Any?>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> Any?>().invoke(connection)
            }
            coEvery { repository.upsertSettings(connection, 3L, ResourceType.TEAM, older, false) } returns
                PreferenceSettingsUpdate(older, null, olderVersion, olderVersion)
            coEvery { repository.upsertSettings(connection, 3L, ResourceType.TEAM, newer, false) } returns
                PreferenceSettingsUpdate(newer, null, newerVersion, newerVersion)
        }

        @Test
        fun `단건 조회가 변경 전에 읽은 값을 늦게 채워도 변경된 설정을 반환한다`() = runBlocking {
            val readDone = CompletableDeferred<Unit>()
            val resumeRead = CompletableDeferred<Unit>()
            coEvery { repository.findSettings(3L, ResourceType.TEAM) } coAnswers {
                readDone.complete(Unit)
                resumeRead.await()
                ResourceSettingState(3L, older, olderVersion)
            }

            val lateRead = async { service.getSettings(ResourceType.TEAM, 3L) }
            readDone.await()
            service.updateSettings(ResourceType.TEAM, 3L, newer).getOrThrow()
            resumeRead.complete(Unit)
            lateRead.await()

            assertEquals(newer, service.getSettings(ResourceType.TEAM, 3L))
        }

        @Test
        fun `묶음 조회가 변경 전에 읽은 값을 늦게 채워도 변경된 설정을 반환한다`() = runBlocking {
            val readDone = CompletableDeferred<Unit>()
            val resumeRead = CompletableDeferred<Unit>()
            coEvery { repository.findSettingsForResources(listOf(3L), ResourceType.TEAM) } coAnswers {
                readDone.complete(Unit)
                resumeRead.await()
                mapOf(3L to ResourceSettingState(3L, older, olderVersion))
            }

            val lateRead = async { service.getSettingsBulk(ResourceType.TEAM, listOf(3L)) }
            readDone.await()
            service.updateSettings(ResourceType.TEAM, 3L, newer).getOrThrow()
            resumeRead.complete(Unit)
            lateRead.await()

            assertEquals(newer, service.getSettings(ResourceType.TEAM, 3L))
        }

        @Test
        fun `이전 변경의 캐시 기록이 늦게 도착해도 나중 변경의 설정을 반환한다`() = runBlocking {
            val olderCommitted = CompletableDeferred<Unit>()
            val resumeOlder = CompletableDeferred<Unit>()
            var transactions = 0
            coEvery { outboxRepository.inTransaction<VersionedSettings>(any()) } coAnswers {
                val committed = firstArg<suspend (SqlConnection) -> VersionedSettings>().invoke(connection)
                if (transactions++ == 0) {
                    olderCommitted.complete(Unit)
                    resumeOlder.await()
                }
                committed
            }

            val olderChange = async { service.updateSettings(ResourceType.TEAM, 3L, older) }
            olderCommitted.await()
            service.updateSettings(ResourceType.TEAM, 3L, newer).getOrThrow()
            resumeOlder.complete(Unit)
            olderChange.await().getOrThrow()

            assertEquals(newer, service.getSettings(ResourceType.TEAM, 3L))
        }

        @Test
        fun `설정 행이 없을 때 늦게 채운 기본값은 그 뒤 변경된 설정을 덮지 않는다`() = runBlocking {
            val readDone = CompletableDeferred<Unit>()
            val resumeRead = CompletableDeferred<Unit>()
            coEvery { repository.findSettingsForResources(listOf(3L), ResourceType.TEAM) } coAnswers {
                readDone.complete(Unit)
                resumeRead.await()
                emptyMap()
            }

            val lateRead = async { service.getSettingsBulk(ResourceType.TEAM, listOf(3L)) }
            readDone.await()
            service.updateSettings(ResourceType.TEAM, 3L, newer).getOrThrow()
            resumeRead.complete(Unit)
            lateRead.await()

            assertEquals(newer, service.getSettings(ResourceType.TEAM, 3L))
        }

        @Test
        fun `만료 전 값을 읽은 조회가 늦게 채워도 만료된 상태를 반환하지 않는다`() = runBlocking {
            val beforeExpiry = JsonObject().put("status", "AWAY").put("timezone", "Asia/Seoul")
            val afterExpiry = JsonObject().put("timezone", "Asia/Seoul")
            val readDone = CompletableDeferred<Unit>()
            val resumeRead = CompletableDeferred<Unit>()
            coEvery { repository.findSettings(19L, ResourceType.ACCOUNT) } coAnswers {
                readDone.complete(Unit)
                resumeRead.await()
                ResourceSettingState(19L, beforeExpiry, olderVersion)
            }
            coEvery { repository.findExpiredAccountStatuses(connection) } returns listOf(19L to "AWAY")
            coEvery { repository.clearExpiredStatuses(connection, listOf(19L)) } returns
                listOf(ResourceSettingState(19L, afterExpiry, newerVersion))

            val lateRead = async { service.getSettings(ResourceType.ACCOUNT, 19L) }
            readDone.await()
            assertEquals(listOf(19L), service.clearExpiredStatuses())
            resumeRead.complete(Unit)
            lateRead.await()

            assertEquals(afterExpiry, service.getSettings(ResourceType.ACCOUNT, 19L))
        }

        @Test
        fun `삭제 전 값을 읽은 조회가 늦게 채워도 삭제 결과를 반환하고 다시 만든 설정은 반영한다`() = runBlocking {
            val inboxRepository = mockk<GithubRepoSettingCommandInboxRepository>(relaxed = true)
            val processor = GithubRepoSettingCommandProcessor(repository, inboxRepository, outboxRepository, cache)
            val beforeDelete = JsonObject().put("label_auto_apply", false)
            val recreatedVersion = Instant.parse("2026-10-28T01:00:00.000002Z")
            val readDone = CompletableDeferred<Unit>()
            val resumeRead = CompletableDeferred<Unit>()
            coEvery { repository.findSettings(77L, ResourceType.GITHUB_REPO) } coAnswers {
                readDone.complete(Unit)
                resumeRead.await()
                ResourceSettingState(77L, beforeDelete, olderVersion)
            }
            coEvery { inboxRepository.findMatches(connection, any()) } returns
                ProcessedGithubRepoSettingCommandMatches(null, null)
            coEvery { repository.deleteGithubRepoSettings(connection, 77L) } returns newerVersion
            coEvery { repository.upsertGithubRepoSettings(connection, 77L, beforeDelete) } returns
                PreferenceSettingsUpdate(beforeDelete, null, recreatedVersion, recreatedVersion)

            val lateRead = async { service.getSettings(ResourceType.GITHUB_REPO, 77L) }
            readDone.await()
            processor.process(githubRepoCommand("delete", GithubRepoSettingCommandType.DELETE, null))
            resumeRead.complete(Unit)
            lateRead.await()

            assertEquals(JsonObject().put("label_auto_apply", true), service.getSettings(ResourceType.GITHUB_REPO, 77L))
            processor.process(githubRepoCommand("recreate", GithubRepoSettingCommandType.UPDATE, false))
            assertEquals(beforeDelete, service.getSettings(ResourceType.GITHUB_REPO, 77L))
        }

        private fun githubRepoCommand(id: String, type: GithubRepoSettingCommandType, labelAutoApply: Boolean?) =
            GithubRepoSettingCommand(id, id, type, 77L, labelAutoApply, 5L, olderVersion)

        private fun keepNewest(resourceId: Long, incoming: VersionedSettings) {
            val current = cached[resourceId]
            if (current == null || incoming.version > current.version) cached[resourceId] = incoming
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
            coVerify(exactly = 0) { outboxRepository.inTransaction<VersionedSettings>(any()) }
        }

        @Test
        fun `팀 닉네임 형식 적용 여부만 바꾸면 현재 예시와 합쳐 검증한다`() = runBlocking {
            val connection = mockk<SqlConnection>()
            val current = JsonObject().put("nickname_format_example", "2학년 홍길동")
            val requested = JsonObject().put("nickname_format_enforced", true)
            val persisted = current.copy().mergeIn(requested)
            val now = Instant.parse("2026-08-30T01:02:03Z")
            coEvery { cache.getSettings(ResourceType.TEAM, 3L) } returns current
            coEvery { outboxRepository.inTransaction<VersionedSettings>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> VersionedSettings>().invoke(connection)
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
            coEvery { outboxRepository.inTransaction<VersionedSettings>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> VersionedSettings>().invoke(connection)
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
            coVerify(exactly = 0) { outboxRepository.inTransaction<VersionedSettings>(any()) }
        }
    }
}
