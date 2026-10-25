package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.LegacyWebhookManifest
import com.cowork.preference.domain.Webhook
import com.cowork.preference.domain.WebhookMigrationIds
import com.cowork.preference.domain.WebhookMigrationMetadata
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.messaging.WebhookLegacyProjectionHandler
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.WebhookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LegacyWebhookMigrationTest {
    @Test
    fun `구형 버전에서 바꾼 웹훅은 최초 이관 이후 재이관에서도 반영한다`() = runBlocking {
        val fixture = Fixture()
        assertNull(fixture.state(fixture.source, 1))
        assertNull(fixture.state(fixture.source.copy(name = "changed", updatedAt = now.plusSeconds(30)), 2))
        assertEquals("changed", fixture.rows[7]?.name)
        assertEquals(fixture.rows[7]?.updatedAt, fixture.metadata[7]?.ownerAppliedUpdatedAt)
        coVerify(exactly = 2) { fixture.outbox.enqueue(fixture.connection, any()) }
    }

    @Test
    fun `Preference 수정과 삭제는 구형 원본 수정 및 삭제보다 우선한다`() = runBlocking {
        val fixture = Fixture()
        fixture.state(fixture.source, 1)
        fixture.rows[7] = fixture.rows.getValue(7).copy(name = "owner", updatedAt = now.plusSeconds(10))
        fixture.state(fixture.source.copy(name = "legacy", updatedAt = now.plusSeconds(20)), 2)
        fixture.manifest(emptySet(), 3)
        assertEquals("owner", fixture.rows[7]?.name)
        assertFalse(fixture.rows.getValue(7).deleted)
        fixture.rows[7] = fixture.rows.getValue(7).copy(deleted = true, updatedAt = now.plusSeconds(1000))
        fixture.state(fixture.source.copy(name = "legacy again", updatedAt = now.plusSeconds(40)), 4)
        assertTrue(fixture.rows.getValue(7).deleted)
        coVerify(exactly = 0) { fixture.repository.applyLegacy(any(), any(), any()) }
    }

    @Test
    fun `이관 기준이 없는 기존 owner는 변경하지 않는다`() = runBlocking {
        val fixture = Fixture()
        fixture.rows[7] = fixture.source.copy(name = "existing owner")
        fixture.state(fixture.source, 1)
        fixture.state(fixture.source.copy(name = "new legacy", updatedAt = now.plusSeconds(30)), 2)
        fixture.manifest(emptySet(), 3)
        assertEquals("existing owner", fixture.rows[7]?.name)
        assertNull(fixture.metadata[7]?.ownerAppliedUpdatedAt)
        coVerify(exactly = 0) { fixture.outbox.enqueue(any(), any()) }
    }

    @Test
    fun `완료된 빈 목록은 미수정 원본만 삭제하고 이전 state 재생은 부활시키지 않는다`() = runBlocking {
        val fixture = Fixture()
        fixture.state(fixture.source, 1)
        fixture.manifest(emptySet(), 2)
        val deleted = fixture.rows.getValue(7)
        assertTrue(deleted.deleted)
        assertTrue(fixture.metadata.getValue(7).sourceDeleted)
        fixture.state(fixture.source, 1)
        fixture.state(fixture.source, 2)
        assertEquals(deleted, fixture.rows[7])
        assertEquals(deleted.updatedAt, fixture.metadata[7]?.ownerAppliedUpdatedAt)
    }

    @Test
    fun `완료 manifest가 압축된 state보다 먼저 와도 누락된 원본을 새로 가져오지 않는다`() = runBlocking {
        val fixture = Fixture()
        fixture.manifest(emptySet(), 2)
        fixture.state(fixture.source, 1)
        assertNull(fixture.rows[7])
        fixture.state(fixture.source.copy(updatedAt = now.plusSeconds(120)), 3)
        assertNotNull(fixture.rows[7])
    }

    @Test
    fun `부분 manifest는 직전 완료 증거를 보존하고 이전 세대 tail chunk와 합치지 않는다`() = runBlocking {
        val fixture = Fixture()
        fixture.state(fixture.source, 1)
        fixture.manifest(setOf(7), 1)
        val completed = fixture.completed
        fixture.manifest(setOf(7), 2, 1, 2)
        fixture.manifest(emptySet(), 3, 0, 2)
        assertEquals(completed, fixture.completed)
        assertFalse(fixture.rows.getValue(7).deleted)
        fixture.manifest(emptySet(), 3, 1, 2)
        assertTrue(fixture.rows.getValue(7).deleted)
        assertEquals(snapshotId(3), fixture.completed?.snapshotId)
    }

    @Test
    fun `최초 이관의 비활성 상태와 Preference 활성 설정은 원본 필드 보정 때 유지한다`() = runBlocking {
        val fixture = Fixture()
        coEvery { fixture.repository.legacyActive(any(), any()) } returns false
        fixture.state(fixture.source, 1)
        assertFalse(fixture.rows.getValue(7).isActive)
        fixture.state(fixture.source.copy(name = "changed", updatedAt = now.plusSeconds(30)), 2)
        assertFalse(fixture.rows.getValue(7).isActive)
        coVerify(exactly = 1) { fixture.repository.legacyActive(any(), any()) }
    }

    @Test
    fun `구형 Preference가 이미 처리한 채널 삭제도 manifest에서 신규 ID까지 정리한다`() = runBlocking {
        val fixture = Fixture()
        val id = WebhookMigrationIds.FIRST_OWNER_ID
        fixture.rows[id] = fixture.source.copy(id = id, name = "owner created", updatedAt = now.plusSeconds(100))
        fixture.channelDeleted = true
        fixture.manifest(emptySet(), 1)
        assertTrue(fixture.rows.getValue(id).deleted)
        assertFalse(fixture.rows.getValue(id).isActive)
        coVerify(exactly = 1) { fixture.outbox.enqueue(fixture.connection, any()) }
    }

    @Test
    fun `이전 관측은 최신 원본과 완료 목록을 되돌리지 않는다`() = runBlocking {
        val fixture = Fixture()
        fixture.state(fixture.source, 1)
        fixture.state(fixture.source.copy(name = "newer", updatedAt = now.plusSeconds(120)), 3)
        fixture.manifest(setOf(7), 3)
        fixture.state(fixture.source.copy(name = "older", updatedAt = now.plusSeconds(60)), 2)
        fixture.manifest(emptySet(), 2)
        assertEquals("newer", fixture.rows[7]?.name)
        assertFalse(fixture.rows.getValue(7).deleted)
        assertEquals(snapshotId(3), fixture.completed?.snapshotId)
    }

    private class Fixture {
        val connection = mockk<SqlConnection>()
        val repository = mockk<WebhookRepository>()
        val channels = mockk<ChannelLifecycleProjectionRepository>()
        val checkpoints = mockk<ProjectionCheckpointRepository>()
        val outbox = mockk<PreferenceOutboxRepository>(relaxed = true)
        val readiness = mockk<ProjectionReadinessView>()
        val rows = mutableMapOf<Long, Webhook>()
        val metadata = mutableMapOf<Long, WebhookMigrationMetadata>()
        val chunks = mutableMapOf<Int, LegacyWebhookManifest>()
        var completed: LegacyWebhookManifest? = null
        var channelDeleted = false
        val source = Webhook(7, 11, "original", null, false, null, true, false, 3, now, now, null)
        val service = LegacyWebhookMigrationService(repository, channels, outbox)
        val handler = WebhookLegacyProjectionHandler(repository, channels, checkpoints, outbox, readiness)

        init {
            every { readiness.isReady } returns true
            coEvery { checkpoints.inTransaction(any(), any()) } coAnswers {
                secondArg<suspend (SqlConnection) -> Unit>().invoke(connection)
            }
            coEvery { channels.findForUpdate(connection, 11) } coAnswers {
                ChannelLifecycleProjection(11, 2, channelDeleted, now, false, "TEXT")
            }
            coEvery { repository.findForUpdate(connection, any()) } coAnswers { rows[secondArg<Long>()] }
            coEvery { repository.migrationMetadata(connection, any()) } coAnswers { metadata[secondArg<Long>()] }
            coEvery { repository.completedManifest(connection, any()) } coAnswers { completed }
            coEvery { repository.saveMigrationMetadata(connection, any()) } coAnswers {
                secondArg<WebhookMigrationMetadata>().let { metadata[it.webhookId] = it }
            }
            coEvery { repository.legacyActive(connection, any()) } returns true
            coEvery { repository.importLegacy(connection, any()) } coAnswers {
                secondArg<Webhook>().let { if (rows.putIfAbsent(it.id, it) == null) it else null }
            }
            coEvery { repository.applyLegacy(connection, any(), any()) } coAnswers {
                val value = secondArg<Webhook>()
                value.copy(isActive = thirdArg(), updatedAt = maxOf(value.updatedAt, rows.getValue(value.id).updatedAt).plusNanos(1000))
                    .also { rows[it.id] = it }
            }
            coEvery { repository.delete(connection, any()) } coAnswers { tombstone(secondArg()) }
            coEvery { repository.deleteChannel(connection, any()) } coAnswers {
                rows.values.filter { !it.deleted && it.channelId == secondArg<Long>() }.map { tombstone(it.id) }
            }
            coEvery { repository.manifestChunk(connection, any()) } coAnswers { chunks[secondArg<LegacyWebhookManifest>().chunkIndex] }
            coEvery { repository.saveManifestChunk(connection, any(), any()) } coAnswers {
                secondArg<LegacyWebhookManifest>().let { chunks[it.chunkIndex] = it }
            }
            coEvery { repository.manifestChunks(connection, any()) } coAnswers { chunks.values.toList() }
            coEvery { repository.channelMigrationMetadata(connection, any()) } coAnswers { metadata.values.toList() }
            coEvery { repository.saveCompletedManifest(connection, any(), any()) } coAnswers { completed = secondArg() }
        }

        suspend fun state(webhook: Webhook, generation: Int): String? {
            val value = JsonObject().put("eventType", "WEBHOOK_LEGACY_STATE").put("schemaVersion", 1)
                .put("webhookId", webhook.id).put("channelId", webhook.channelId).put("createdBy", webhook.createdBy)
                .put("createdAt", webhook.createdAt.toString()).put("name", webhook.name).put("avatarUrl", webhook.avatarUrl)
                .put("isSecure", webhook.isSecure).put("secretHash", webhook.secretHash).put("occurredAt", webhook.updatedAt.toString())
                .put("observedAt", now.plusSeconds(generation * 60L).toString()).put("snapshotId", snapshotId(generation)).put("snapshot", true)
            return handler.apply(ProjectionCheckpoint("g", "legacy", 0, generation.toLong(), "topic"), webhook.id.toString(), value.encode())
        }

        suspend fun manifest(ids: Set<Long>, generation: Int, index: Int = 0, count: Int = 1) {
            service.manifest(connection, LegacyWebhookManifest(11, ids, snapshotId(generation), now.plusSeconds(generation * 60L), index, count))
        }

        private fun tombstone(id: Long): Webhook = rows.getValue(id).let {
            it.copy(deleted = true, isActive = false, secretHash = null, updatedAt = it.updatedAt.plusNanos(1000))
        }.also { rows[id] = it }
    }

    companion object {
        private val now = Instant.parse("2026-10-25T10:00:00Z")
        private fun snapshotId(generation: Int) = "00000000-0000-0000-0000-${generation.toString().padStart(12, '0')}"
    }
}
