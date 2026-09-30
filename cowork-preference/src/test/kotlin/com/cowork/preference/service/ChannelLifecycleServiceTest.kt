package com.cowork.preference.service

import com.cowork.preference.domain.ChannelRolePolicyState
import com.cowork.preference.messaging.ChannelLifecycleEvent
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
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

class ChannelLifecycleServiceTest {

    private lateinit var fixture: Fixture

    @BeforeEach
    fun setUp() {
        fixture = Fixture()
    }

    @Nested
    inner class Apply {

        @Test
        fun `채널 삭제가 반영되면 해당 팀 채널의 모든 역할 정책을 tombstone으로 바꾸고 DELETE 상태를 적재한다`() =
            runBlocking {
                val events = slot<Iterable<PreferenceEvent>>()
                coEvery { fixture.projectionRepository.apply(fixture.connection, deletedEvent) } returns true
                coEvery {
                    fixture.policyRepository.findPoliciesByChannel(fixture.connection, TEAM_ID, CHANNEL_ID)
                } returns listOf(activePolicy(ROLE_ID), activePolicy(OTHER_ROLE_ID))
                listOf(ROLE_ID, OTHER_ROLE_ID).forEach { roleId ->
                    coEvery {
                        fixture.policyRepository.deletePolicy(fixture.connection, TEAM_ID, CHANNEL_ID, roleId)
                    } returns tombstone(roleId)
                }
                coEvery { fixture.outboxRepository.enqueueAll(fixture.connection, capture(events)) } returns Unit

                fixture.service.apply(fixture.connection, deletedEvent)

                val stateEvents = events.captured.toList()
                assertEquals(2, stateEvents.size)
                assertTrue(stateEvents.all { it.topic == PreferenceEvents.CHANNEL_ROLE_POLICY_STATE_TOPIC })
                assertTrue(stateEvents.all { it.payload.getValue("permissions") == null })
                assertEquals(
                    setOf(ROLE_ID, OTHER_ROLE_ID),
                    stateEvents.map { it.payload.getLong("roleId") }.toSet(),
                )
            }

        @Test
        fun `이미 더 높은 version이 반영돼 무시된 event는 정책을 정리하지 않는다`() = runBlocking {
            coEvery { fixture.projectionRepository.apply(fixture.connection, deletedEvent) } returns false

            fixture.service.apply(fixture.connection, deletedEvent)

            coVerify(exactly = 0) { fixture.policyRepository.findPoliciesByChannel(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.policyRepository.deletePolicy(any(), any(), any(), any()) }
        }

        @Test
        fun `활성 채널 상태는 정책을 정리하지 않는다`() = runBlocking {
            val activeEvent = deletedEvent.copy(deleted = false)
            coEvery { fixture.projectionRepository.apply(fixture.connection, activeEvent) } returns true

            fixture.service.apply(fixture.connection, activeEvent)

            coVerify(exactly = 0) { fixture.policyRepository.findPoliciesByChannel(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.policyRepository.deletePolicy(any(), any(), any(), any()) }
        }
    }

    private class Fixture {
        val projectionRepository = mockk<ChannelLifecycleProjectionRepository>()
        val policyRepository = mockk<ChannelRolePolicyRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>(relaxed = true)
        val connection = mockk<SqlConnection>()
        val service = ChannelLifecycleService(projectionRepository, policyRepository, outboxRepository)
    }

    private companion object {
        const val TEAM_ID = 7L
        const val CHANNEL_ID = 8L
        const val ROLE_ID = 9L
        const val OTHER_ROLE_ID = 10L

        val deletedEvent = ChannelLifecycleEvent(
            channelId = CHANNEL_ID,
            teamId = TEAM_ID,
            deleted = true,
            occurredAt = Instant.parse("2026-08-30T01:02:03Z"),
        )

        fun activePolicy(roleId: Long) = ChannelRolePolicyState(
            teamId = TEAM_ID,
            channelId = CHANNEL_ID,
            roleId = roleId,
            permissions = JsonObject().put("message_read", true),
            stateOccurredAt = Instant.parse("2026-08-30T01:00:00Z"),
        )

        fun tombstone(roleId: Long) = activePolicy(roleId).copy(
            permissions = null,
            stateOccurredAt = Instant.parse("2026-08-30T01:02:04Z"),
        )
    }
}
