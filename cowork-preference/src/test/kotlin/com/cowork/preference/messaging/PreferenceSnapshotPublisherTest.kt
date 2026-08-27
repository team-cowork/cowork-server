package com.cowork.preference.messaging

import com.cowork.preference.repository.GithubRepoSettingState
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PreferenceSnapshotPublisherTest {

    @Test
    fun `GitHub repository의 모든 full snapshot은 active state와 durable tombstone을 함께 발행한다`() = runBlocking {
        val notificationRepository = mockk<NotificationRepository>()
        val preferenceRepository = mockk<PreferenceRepository>()
        val teamRoleRepository = mockk<TeamRoleRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val topicIdentity = mockk<ProjectionTopicIdentityProvider>()
        val connection = mockk<SqlConnection>()
        val activeVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        val deletedVersion = Instant.parse("2026-08-27T02:03:04.123456Z")
        val activeSettings = JsonObject().put("label_auto_apply", false)
        val page = listOf(
            GithubRepoSettingState(101L, activeSettings, activeVersion),
            GithubRepoSettingState(202L, null, deletedVersion),
        )
        coEvery { outboxRepository.withProjectionSnapshotLock(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
            true
        }
        coEvery { outboxRepository.inTransaction<Any>(connection, any()) } coAnswers {
            secondArg<suspend (SqlConnection) -> Any>().invoke(connection)
        }
        coEvery {
            notificationRepository.findNotificationPage(connection, -1L, -1L, 500)
        } returns emptyList()
        coEvery { teamRoleRepository.findRolePage(connection, 0L, 500) } returns emptyList()
        coEvery {
            teamRoleRepository.findAssignmentPage(connection, -1L, -1L, -1L, 500)
        } returns emptyList()
        coEvery { preferenceRepository.findGithubRepoSettingPage(connection, 0L, 500) } returns page
        coEvery { preferenceRepository.findGithubRepoSettingPage(connection, 202L, 500) } returns emptyList()
        coEvery { topicIdentity.topicState(any()) } returns ProjectionTopicState(
            topicId = "00000000-0000-0000-0000-000000000001",
            ranges = mapOf(0 to ProjectionBrokerRange(0, 0)),
        )
        val published = mutableListOf<PreferenceEvent>()
        coEvery { outboxRepository.enqueueAll(connection, any()) } coAnswers {
            published += secondArg<Iterable<PreferenceEvent>>()
        }
        val publisher = PreferenceSnapshotPublisher(
            notificationRepository,
            preferenceRepository,
            teamRoleRepository,
            outboxRepository,
            topicIdentity,
        )

        publisher.publishAllIfLeader()
        publisher.publishAllIfLeader()

        val states = published.filter {
            it.topic == PreferenceEvents.GITHUB_REPO_SETTING_STATE_TOPIC &&
                it.payload.getString("eventType") != PreferenceEvents.SNAPSHOT_COMPLETED_EVENT_TYPE
        }
        assertEquals(4, states.size)
        val active = states.filter { it.key == "101" }
        assertEquals(2, active.size)
        active.forEach { event ->
            assertEquals("UPSERT", event.payload.getString("eventType"))
            assertEquals(false, event.payload.getJsonObject("settings").getBoolean("label_auto_apply"))
            assertEquals(activeVersion, event.occurredAt)
            assertTrue(event.payload.getBoolean("snapshot"))
        }
        val deleted = states.filter { it.key == "202" }
        assertEquals(2, deleted.size)
        deleted.forEach { event ->
            assertEquals("DELETE", event.payload.getString("eventType"))
            assertNull(event.payload.getValue("settings"))
            assertEquals(deletedVersion, event.occurredAt)
            assertTrue(event.payload.getBoolean("snapshot"))
        }
        coVerifyOrder {
            preferenceRepository.findGithubRepoSettingPage(connection, 0L, 500)
            preferenceRepository.findGithubRepoSettingPage(connection, 202L, 500)
            preferenceRepository.findGithubRepoSettingPage(connection, 0L, 500)
            preferenceRepository.findGithubRepoSettingPage(connection, 202L, 500)
        }
    }
}
