package com.cowork.preference.messaging

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.ChannelRolePolicyState
import com.cowork.preference.domain.TeamRoleAssignmentState
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.domain.TeamRoleMemberFence
import com.cowork.preference.domain.TeamRoleState
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.GithubRepoSettingState
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class PreferenceSnapshotPublisherTest {

    @Test
    fun `snapshot marker is not produced before the upstream member projection is ready`() = runBlocking {
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val publisher = PreferenceSnapshotPublisher(
            notificationRepository = mockk(),
            preferenceRepository = mockk(),
            teamRoleRepository = mockk(),
            channelRolePolicyRepository = mockk(),
            outboxRepository = outboxRepository,
            topicIdentity = mockk(),
            upstreamReadiness = ProjectionReadiness(),
        )

        publisher.publishAllIfLeader()

        coVerify(exactly = 0) { outboxRepository.withProjectionSnapshotLock(any()) }
    }

    @Test
    fun `repeated snapshot replays revoke role and team deletes without reviving a rejoined member`() = runBlocking {
        val notificationRepository = mockk<NotificationRepository>()
        val preferenceRepository = mockk<PreferenceRepository>()
        val teamRoleRepository = mockk<TeamRoleRepository>()
        val channelRolePolicyRepository = mockk<ChannelRolePolicyRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val topicIdentity = mockk<ProjectionTopicIdentityProvider>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val baseVersion = Instant.parse("2026-08-30T01:02:03.123456Z")
        val roleStates = (1L..503L).map { roleId ->
            val version = baseVersion.plusNanos(roleId * 1_000L)
            TeamRoleState(
                teamId = 1L,
                roleId = roleId,
                role = if (roleId % 2L == 0L) {
                    TeamRoleDefinition(
                        id = roleId,
                        teamId = 1L,
                        name = "role-$roleId",
                        colorHex = "#112233",
                        priority = roleId.toInt(),
                        mentionable = false,
                        permissions = emptySet(),
                        createdAt = version.atOffset(ZoneOffset.UTC),
                        updatedAt = version.atOffset(ZoneOffset.UTC),
                    )
                } else {
                    null
                },
                stateOccurredAt = version,
            )
        }
        val assignmentStates = (1L..503L).map { accountId ->
            val version = baseVersion.plusSeconds(2).plusNanos(accountId * 1_000L)
            TeamRoleAssignmentState(
                teamId = 1L,
                accountId = accountId,
                roleId = 9L,
                assignment = if (accountId % 2L == 0L) {
                    AccountTeamRole(
                        accountId = accountId,
                        teamId = 1L,
                        roleId = 9L,
                        sourceMembershipVersion = baseVersion,
                        updatedAt = version.atOffset(ZoneOffset.UTC),
                    )
                } else {
                    null
                },
                stateOccurredAt = version,
            )
        }
        val memberFences = (1L..503L).map { accountId ->
            TeamRoleMemberFence(
                teamId = 1L,
                accountId = accountId,
                stateOccurredAt = baseVersion.plusSeconds(1).plusNanos(accountId * 1_000L),
            )
        }
        every { readiness.isReady } returns true
        coEvery { outboxRepository.withProjectionSnapshotLock(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
            true
        }
        coEvery { outboxRepository.inTransaction<Any>(connection, any()) } coAnswers {
            secondArg<suspend (SqlConnection) -> Any>().invoke(connection)
        }
        coEvery { notificationRepository.findNotificationPage(connection, -1L, -1L, 500) } returns emptyList()
        coEvery { teamRoleRepository.findRoleStatePage(connection, -1L, -1L, 500) } returns roleStates.take(500)
        coEvery { teamRoleRepository.findRoleStatePage(connection, 1L, 500L, 500) } returns roleStates.drop(500)
        coEvery { teamRoleRepository.findRoleStatePage(connection, 1L, 503L, 500) } returns emptyList()
        coEvery {
            teamRoleRepository.findAssignmentStatePage(connection, -1L, -1L, -1L, 500)
        } returns assignmentStates.take(500)
        coEvery {
            teamRoleRepository.findAssignmentStatePage(connection, 1L, 500L, 9L, 500)
        } returns assignmentStates.drop(500)
        coEvery {
            teamRoleRepository.findAssignmentStatePage(connection, 1L, 503L, 9L, 500)
        } returns emptyList()
        coEvery {
            teamRoleRepository.findMemberFencePage(connection, -1L, -1L, 500)
        } returns memberFences.take(500)
        coEvery {
            teamRoleRepository.findMemberFencePage(connection, 1L, 500L, 500)
        } returns memberFences.drop(500)
        coEvery {
            teamRoleRepository.findMemberFencePage(connection, 1L, 503L, 500)
        } returns emptyList()
        coEvery { preferenceRepository.findGithubRepoSettingPage(connection, 0L, 500) } returns emptyList()
        coEvery {
            channelRolePolicyRepository.findPolicyPage(connection, -1L, -1L, -1L, 500)
        } returns emptyList()
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
            channelRolePolicyRepository,
            outboxRepository,
            topicIdentity,
            readiness,
        )

        publisher.publishAllIfLeader()
        publisher.publishAllIfLeader()

        val teamEvents = published.filter { it.topic == PreferenceEvents.TEAM_ROLE_TOPIC }
        val stateEvents = teamEvents.filter {
            it.payload.getString("eventType") != PreferenceEvents.SNAPSHOT_COMPLETED_EVENT_TYPE
        }
        val expectedKeys = buildSet {
            addAll(roleStates.map { "role:${it.teamId}:${it.roleId}" })
            addAll(assignmentStates.map { "assignment:${it.teamId}:${it.accountId}:${it.roleId}" })
            addAll(memberFences.map { "member:${it.teamId}:${it.accountId}" })
        }
        assertEquals(3_018, stateEvents.size)
        assertEquals(1_509, expectedKeys.size)
        assertEquals(expectedKeys, stateEvents.take(1_509).map(PreferenceEvent::key).toSet())
        assertEquals(expectedKeys, stateEvents.drop(1_509).map(PreferenceEvent::key).toSet())
        assertTrue(stateEvents.groupingBy(PreferenceEvent::key).eachCount().values.all { it == 2 })
        assertEquals(
            listOf(1_509, 3_019),
            teamEvents.mapIndexedNotNull { index, event ->
                index.takeIf {
                    event.payload.getString("eventType") == PreferenceEvents.SNAPSHOT_COMPLETED_EVENT_TYPE
                }
            },
        )
        assertEquals(504, stateEvents.count { it.payload.getString("eventType") == "ROLE_DELETED" })
        assertEquals(504, stateEvents.count { it.payload.getString("eventType") == "ASSIGNMENT_DELETED" })
        assertEquals(1_006, stateEvents.count { it.payload.getString("eventType") == "MEMBER_ASSIGNMENTS_DELETED" })
        val firstSnapshot = stateEvents.take(1_509)
        val deletedAssignment = firstSnapshot.single { it.key == "assignment:1:1:9" }
        val deletedMemberFence = firstSnapshot.single { it.key == "member:1:1" }
        assertEquals("ASSIGNMENT_DELETED", deletedAssignment.payload.getString("eventType"))
        assertTrue(deletedAssignment.occurredAt.isAfter(deletedMemberFence.occurredAt))
        val reassigned = firstSnapshot.single { it.key == "assignment:1:2:9" }
        val reassignedMemberFence = firstSnapshot.single { it.key == "member:1:2" }
        assertEquals("ASSIGNMENT_UPSERTED", reassigned.payload.getString("eventType"))
        assertTrue(reassigned.occurredAt.isAfter(reassignedMemberFence.occurredAt))
    }

    @Test
    fun `each full snapshot publishes interleaved active and retained tombstone pages exactly once`() = runBlocking {
        val notificationRepository = mockk<NotificationRepository>()
        val preferenceRepository = mockk<PreferenceRepository>()
        val teamRoleRepository = mockk<TeamRoleRepository>()
        val channelRolePolicyRepository = mockk<ChannelRolePolicyRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val topicIdentity = mockk<ProjectionTopicIdentityProvider>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val version = Instant.parse("2026-08-30T01:02:03.123456Z")
        val policies = (1L..503L).map { channelId ->
            ChannelRolePolicyState(
                teamId = 1L,
                channelId = channelId,
                roleId = 9L,
                permissions = if (channelId % 2L == 0L) {
                    JsonObject().put("message_read", channelId % 4L == 0L)
                } else {
                    null
                },
                stateOccurredAt = version.plusNanos(channelId * 1_000L),
            )
        }
        every { readiness.isReady } returns true
        coEvery { outboxRepository.withProjectionSnapshotLock(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
            true
        }
        coEvery { outboxRepository.inTransaction<Any>(connection, any()) } coAnswers {
            secondArg<suspend (SqlConnection) -> Any>().invoke(connection)
        }
        coEvery { notificationRepository.findNotificationPage(connection, -1L, -1L, 500) } returns emptyList()
        coEvery { teamRoleRepository.findRoleStatePage(connection, -1L, -1L, 500) } returns emptyList()
        coEvery {
            teamRoleRepository.findAssignmentStatePage(connection, -1L, -1L, -1L, 500)
        } returns emptyList()
        coEvery { teamRoleRepository.findMemberFencePage(connection, -1L, -1L, 500) } returns emptyList()
        coEvery { preferenceRepository.findGithubRepoSettingPage(connection, 0L, 500) } returns emptyList()
        coEvery {
            channelRolePolicyRepository.findPolicyPage(connection, -1L, -1L, -1L, 500)
        } returns policies.take(500)
        coEvery {
            channelRolePolicyRepository.findPolicyPage(connection, 1L, 500L, 9L, 500)
        } returns policies.drop(500)
        coEvery {
            channelRolePolicyRepository.findPolicyPage(connection, 1L, 503L, 9L, 500)
        } returns emptyList()
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
            channelRolePolicyRepository,
            outboxRepository,
            topicIdentity,
            readiness,
        )

        publisher.publishAllIfLeader()
        publisher.publishAllIfLeader()

        val states = published.filter {
            it.topic == PreferenceEvents.CHANNEL_ROLE_POLICY_STATE_TOPIC &&
                it.payload.getString("eventType") != PreferenceEvents.SNAPSHOT_COMPLETED_EVENT_TYPE
        }
        assertEquals(1_006, states.size)
        assertEquals(503, states.map(PreferenceEvent::key).toSet().size)
        val expectedKeys = policies.map { "policy:${it.teamId}:${it.channelId}:${it.roleId}" }.toSet()
        states.chunked(503).forEach { snapshot ->
            assertEquals(expectedKeys, snapshot.map(PreferenceEvent::key).toSet())
        }
        assertTrue(states.groupingBy(PreferenceEvent::key).eachCount().values.all { it == 2 })
        assertTrue(states.all { it.payload.getBoolean("snapshot") })
        assertEquals(504, states.count { it.payload.getString("eventType") == "DELETE" })
        assertEquals(502, states.count { it.payload.getString("eventType") == "UPSERT" })
        val completionMarkers = published.filter {
            it.topic == PreferenceEvents.CHANNEL_ROLE_POLICY_STATE_TOPIC &&
                it.payload.getString("eventType") == PreferenceEvents.SNAPSHOT_COMPLETED_EVENT_TYPE
        }
        assertEquals(2, completionMarkers.size)
        assertTrue(completionMarkers.all { it.partition == 0 })
        coVerifyOrder {
            channelRolePolicyRepository.findPolicyPage(connection, -1L, -1L, -1L, 500)
            channelRolePolicyRepository.findPolicyPage(connection, 1L, 500L, 9L, 500)
            channelRolePolicyRepository.findPolicyPage(connection, 1L, 503L, 9L, 500)
            channelRolePolicyRepository.findPolicyPage(connection, -1L, -1L, -1L, 500)
            channelRolePolicyRepository.findPolicyPage(connection, 1L, 500L, 9L, 500)
            channelRolePolicyRepository.findPolicyPage(connection, 1L, 503L, 9L, 500)
        }
    }

    @Test
    fun `GitHub repository의 모든 full snapshot은 active state와 durable tombstone을 함께 발행한다`() = runBlocking {
        val notificationRepository = mockk<NotificationRepository>()
        val preferenceRepository = mockk<PreferenceRepository>()
        val teamRoleRepository = mockk<TeamRoleRepository>()
        val channelRolePolicyRepository = mockk<ChannelRolePolicyRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val topicIdentity = mockk<ProjectionTopicIdentityProvider>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val activeVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        val deletedVersion = Instant.parse("2026-08-27T02:03:04.123456Z")
        val activeSettings = JsonObject().put("label_auto_apply", false)
        val page = listOf(
            GithubRepoSettingState(101L, activeSettings, activeVersion),
            GithubRepoSettingState(202L, null, deletedVersion),
        )
        every { readiness.isReady } returns true
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
        coEvery { teamRoleRepository.findRoleStatePage(connection, -1L, -1L, 500) } returns emptyList()
        coEvery {
            teamRoleRepository.findAssignmentStatePage(connection, -1L, -1L, -1L, 500)
        } returns emptyList()
        coEvery { teamRoleRepository.findMemberFencePage(connection, -1L, -1L, 500) } returns emptyList()
        coEvery { preferenceRepository.findGithubRepoSettingPage(connection, 0L, 500) } returns page
        coEvery { preferenceRepository.findGithubRepoSettingPage(connection, 202L, 500) } returns emptyList()
        coEvery {
            channelRolePolicyRepository.findPolicyPage(connection, -1L, -1L, -1L, 500)
        } returns emptyList()
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
            channelRolePolicyRepository,
            outboxRepository,
            topicIdentity,
            readiness,
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
