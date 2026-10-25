package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.Webhook
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
import org.junit.jupiter.api.Test
import java.time.Instant

class LegacyWebhookMigrationTest {
    @Test
    fun `구형 버전에서 바꾼 웹훅은 최초 이관 이후 재이관에서도 반영한다`() = runBlocking {
        val repository = mockk<WebhookRepository>(relaxed = true)
        val channels = mockk<ChannelLifecycleProjectionRepository>()
        val checkpoints = mockk<ProjectionCheckpointRepository>()
        val outbox = mockk<PreferenceOutboxRepository>(relaxed = true)
        val readiness = mockk<ProjectionReadinessView>()
        val connection = mockk<SqlConnection>()
        val now = Instant.parse("2026-10-25T10:00:00Z")
        val first = Webhook(7, 11, "original", null, false, null, true, false, 3, now, now, null)
        every { readiness.isReady } returns true
        coEvery { checkpoints.inTransaction(any(), any()) } coAnswers {
            secondArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { channels.findForUpdate(connection, 11) } returns ChannelLifecycleProjection(11, 2, false, now, false, "TEXT")
        coEvery { repository.legacyActive(connection, 11) } returns true
        coEvery { repository.importLegacy(connection, any()) } returnsMany listOf(first, null)
        val handler = WebhookLegacyProjectionHandler(repository, channels, checkpoints, outbox, readiness)
        fun state(name: String, version: Instant, observedAt: Instant, snapshot: String) = JsonObject()
            .put("eventType", "WEBHOOK_LEGACY_STATE").put("schemaVersion", 1)
            .put("webhookId", 7L).put("channelId", 11L).put("createdBy", 3L).put("createdAt", now.toString())
            .put("name", name).put("avatarUrl", null).put("isSecure", false).put("secretHash", null)
            .put("occurredAt", version.toString()).put("observedAt", observedAt.toString())
            .put("snapshotId", snapshot).put("snapshot", true).encode()
        handler.apply(ProjectionCheckpoint("g", "legacy", 0, 1, "topic"), "7", state("original", now, now, "00000000-0000-0000-0000-000000000001"))
        handler.apply(ProjectionCheckpoint("g", "legacy", 0, 2, "topic"), "7", state("changed", now.plusSeconds(30), now.plusSeconds(60), "00000000-0000-0000-0000-000000000002"))
        coVerify(exactly = 2) { outbox.enqueue(connection, any()) }
    }
}
