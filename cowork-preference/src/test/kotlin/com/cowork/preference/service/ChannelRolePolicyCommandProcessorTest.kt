package com.cowork.preference.service

import com.cowork.preference.domain.ChannelRolePolicyState
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.ChannelRolePolicyCommand
import com.cowork.preference.messaging.ChannelRolePolicyCommandType
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.repository.ChannelRolePolicyCommandInboxRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProcessedChannelRolePolicyCommand
import com.cowork.preference.repository.ProcessedChannelRolePolicyCommandMatches
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class ChannelRolePolicyCommandProcessorTest {

    @Test
    fun `UPSERT canonicalizes repository event result and inbox permissions`() = runBlocking {
        val fixture = Fixture()
        val rawCommand = COMMAND.copy(
            operationId = "00000000-0000-0000-0000-000000000004",
            idempotencyKey = "policy-default-1",
            requestHash = "c".repeat(64),
            permissions = JsonObject().put("message_write", true),
        )
        val canonicalPermissions = JsonObject().put("message_read", false)
        val canonicalCommand = rawCommand.copy(permissions = canonicalPermissions)
        val stateVersion = Instant.parse("2026-08-30T01:02:07.123456Z")
        val repositoryPermissions = slot<JsonObject>()
        val states = slot<Iterable<PreferenceEvent>>()
        val inboxCommand = slot<ChannelRolePolicyCommand>()
        val storedResult = slot<JsonObject>()
        fixture.stubCommon(canonicalCommand)
        coEvery {
            fixture.policyRepository.upsertPolicy(
                fixture.connection,
                canonicalCommand.teamId,
                canonicalCommand.channelId,
                canonicalCommand.roleId,
                capture(repositoryPermissions),
            )
        } returns ChannelRolePolicyState(
            canonicalCommand.teamId,
            canonicalCommand.channelId,
            canonicalCommand.roleId,
            canonicalPermissions.copy().put("legacy_permission", true),
            stateVersion,
        )
        coEvery { fixture.outboxRepository.enqueueAll(fixture.connection, capture(states)) } returns Unit
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, any()) } returns Unit
        coEvery {
            fixture.inboxRepository.insert(fixture.connection, capture(inboxCommand), capture(storedResult))
        } returns Unit

        fixture.processor.process(rawCommand)

        assertEquals(canonicalPermissions, repositoryPermissions.captured)
        assertEquals(canonicalPermissions, inboxCommand.captured.permissions)
        val statePermissions = states.captured.single().payload.getJsonObject("permissions")
        assertEquals(canonicalPermissions, statePermissions)
        assertEquals(canonicalPermissions, storedResult.captured.getJsonObject("permissions"))
        assertEquals(setOf("message_read"), repositoryPermissions.captured.fieldNames())
        assertEquals(setOf("message_read"), statePermissions.fieldNames())
    }

    @Test
    fun `UPSERT validates membership and same-team role then stores state result and inbox atomically`() = runBlocking {
        val fixture = Fixture()
        val stateVersion = Instant.parse("2026-08-30T01:02:05.123456Z")
        val state = ChannelRolePolicyState(
            COMMAND.teamId,
            COMMAND.channelId,
            COMMAND.roleId,
            COMMAND.permissions,
            stateVersion,
        )
        val stateEvents = slot<Iterable<PreferenceEvent>>()
        val resultEvent = slot<PreferenceEvent>()
        val storedResult = slot<JsonObject>()
        fixture.stubCommon(COMMAND)
        coEvery {
            fixture.policyRepository.upsertPolicy(
                fixture.connection,
                COMMAND.teamId,
                COMMAND.channelId,
                COMMAND.roleId,
                requireNotNull(COMMAND.permissions),
            )
        } returns state
        coEvery { fixture.outboxRepository.enqueueAll(fixture.connection, capture(stateEvents)) } returns Unit
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, capture(resultEvent)) } returns Unit
        coEvery { fixture.inboxRepository.insert(fixture.connection, COMMAND, capture(storedResult)) } returns Unit

        fixture.processor.process(COMMAND)

        val stateEvent = stateEvents.captured.single()
        assertEquals("preference.channel-role-policy.changed", stateEvent.topic)
        assertEquals("policy:7:8:9", stateEvent.key)
        assertEquals("UPSERT", stateEvent.payload.getString("eventType"))
        assertEquals(false, stateEvent.payload.getJsonObject("permissions").getBoolean("message_read"))
        assertEquals(false, stateEvent.payload.getBoolean("snapshot"))
        assertEquals("preference.channel-role-policy.command-result", resultEvent.captured.topic)
        assertEquals("SUCCEEDED", storedResult.captured.getString("status"))
        assertEquals(stateVersion.toString(), storedResult.captured.getJsonObject("projection").getString("occurredAt"))
        assertNotEquals(
            storedResult.captured.getString("occurredAt"),
            storedResult.captured.getJsonObject("projection").getString("occurredAt"),
        )
        coVerifyOrder {
            fixture.memberRepository.find(fixture.connection, COMMAND.teamId, COMMAND.actorId)
            fixture.roleRepository.findRole(fixture.connection, COMMAND.teamId, COMMAND.roleId)
            fixture.policyRepository.upsertPolicy(
                fixture.connection,
                COMMAND.teamId,
                COMMAND.channelId,
                COMMAND.roleId,
                any(),
            )
            fixture.outboxRepository.enqueueAll(fixture.connection, any())
            fixture.outboxRepository.enqueue(fixture.connection, any())
            fixture.inboxRepository.insert(fixture.connection, COMMAND, any())
        }
    }

    @Test
    fun `role from another team is rejected without mutating policy`() = runBlocking {
        val fixture = Fixture()
        val storedResult = slot<JsonObject>()
        fixture.stubCommon(COMMAND, role = null)
        coEvery { fixture.outboxRepository.enqueueAll(fixture.connection, any()) } returns Unit
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, any()) } returns Unit
        coEvery { fixture.inboxRepository.insert(fixture.connection, COMMAND, capture(storedResult)) } returns Unit

        fixture.processor.process(COMMAND)

        assertEquals("FAILED", storedResult.captured.getString("status"))
        assertEquals("ROLE_NOT_FOUND", storedResult.captured.getJsonObject("error").getString("code"))
        coVerify(exactly = 0) { fixture.policyRepository.upsertPolicy(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.policyRepository.deletePolicy(any(), any(), any(), any()) }
    }

    @Test
    fun `actor projection behind command version retries without terminal result`() = runBlocking {
        val fixture = Fixture()
        fixture.stubCommon(
            COMMAND,
            memberVersion = COMMAND.actorMembershipVersion.minusSeconds(1),
        )

        val error = runCatching { fixture.processor.process(COMMAND) }.exceptionOrNull()

        assertTrue(error is ChannelRolePolicyProjectionNotReadyException)
        coVerify(exactly = 0) { fixture.outboxRepository.enqueueAll(any(), any()) }
        coVerify(exactly = 0) { fixture.inboxRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `DELETE writes a retained state tombstone and terminal result`() = runBlocking {
        val fixture = Fixture()
        val command = COMMAND.copy(
            operationId = "00000000-0000-0000-0000-000000000002",
            idempotencyKey = "policy-delete-1",
            requestHash = "b".repeat(64),
            commandType = ChannelRolePolicyCommandType.DELETE,
            permissions = null,
        )
        val stateVersion = Instant.parse("2026-08-30T01:02:06.123456Z")
        val tombstone = ChannelRolePolicyState(
            command.teamId,
            command.channelId,
            command.roleId,
            null,
            stateVersion,
        )
        val states = slot<Iterable<PreferenceEvent>>()
        val storedResult = slot<JsonObject>()
        fixture.stubCommon(command)
        coEvery {
            fixture.policyRepository.deletePolicy(
                fixture.connection,
                command.teamId,
                command.channelId,
                command.roleId,
            )
        } returns tombstone
        coEvery { fixture.outboxRepository.enqueueAll(fixture.connection, capture(states)) } returns Unit
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, any()) } returns Unit
        coEvery { fixture.inboxRepository.insert(fixture.connection, command, capture(storedResult)) } returns Unit

        fixture.processor.process(command)

        val event = states.captured.single()
        assertEquals("DELETE", event.payload.getString("eventType"))
        assertEquals(null, event.payload.getValue("permissions"))
        assertTrue(storedResult.captured.getJsonObject("projection").getBoolean("deleted"))
    }

    @Test
    fun `unknown permission을 제거한 동일 요청은 terminal result를 재생함`() = runBlocking {
        val fixture = Fixture()
        val replay = COMMAND.copy(
            operationId = "00000000-0000-0000-0000-000000000003",
            permissions = requireNotNull(COMMAND.permissions).copy().put("future_permission", JsonObject()),
        )
        val canonicalReplay = replay.copy(permissions = COMMAND.permissions)
        val storedResult = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", COMMAND.operationId)
            .put("status", "SUCCEEDED")
            .put("occurredAt", "2026-08-30T01:02:06.123456Z")
        val existing = ProcessedChannelRolePolicyCommand(
            operationId = COMMAND.operationId,
            actorId = COMMAND.actorId,
            idempotencyKey = COMMAND.idempotencyKey,
            requestHash = COMMAND.requestHash,
            commandType = COMMAND.commandType,
            teamId = COMMAND.teamId,
            channelId = COMMAND.channelId,
            roleId = COMMAND.roleId,
            actorMembershipVersion = COMMAND.actorMembershipVersion,
            permissions = COMMAND.permissions,
            result = storedResult,
        )
        val replayed = slot<PreferenceEvent>()
        coEvery { fixture.outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(fixture.connection)
        }
        coEvery { fixture.inboxRepository.findMatches(fixture.connection, canonicalReplay) } returns
            ProcessedChannelRolePolicyCommandMatches(null, existing)
        coEvery { fixture.outboxRepository.enqueue(fixture.connection, capture(replayed)) } returns Unit

        fixture.processor.process(replay)

        assertEquals(replay.operationId, replayed.captured.payload.getString("operationId"))
        assertEquals("SUCCEEDED", replayed.captured.payload.getString("status"))
        coVerify(exactly = 0) { fixture.policyRepository.upsertPolicy(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.inboxRepository.insert(any(), any(), any()) }
    }

    private class Fixture {
        val policyRepository = mockk<ChannelRolePolicyRepository>()
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<ChannelRolePolicyCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val processor = ChannelRolePolicyCommandProcessor(
            policyRepository,
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        fun stubCommon(
            command: ChannelRolePolicyCommand,
            memberVersion: Instant = command.actorMembershipVersion,
            role: TeamRoleDefinition? = ROLE,
        ) {
            every { readiness.isReady } returns true
            coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
            }
            coEvery { inboxRepository.findMatches(connection, command) } returns
                ProcessedChannelRolePolicyCommandMatches(null, null)
            coEvery { roleRepository.isTeamDeleted(connection, command.teamId) } returns false
            coEvery { memberRepository.find(connection, command.teamId, command.actorId) } returns
                TeamMemberProjection(
                    teamId = command.teamId,
                    accountId = command.actorId,
                    builtInRole = "ADMIN",
                    deleted = false,
                    sourceOccurredAt = memberVersion,
                )
            coEvery { roleRepository.findRole(connection, command.teamId, command.roleId) } returns role
        }
    }

    private companion object {
        val COMMAND = ChannelRolePolicyCommand(
            operationId = "00000000-0000-0000-0000-000000000001",
            idempotencyKey = "policy-upsert-1",
            requestHash = "a".repeat(64),
            commandType = ChannelRolePolicyCommandType.UPSERT,
            teamId = 7L,
            channelId = 8L,
            roleId = 9L,
            actorId = 10L,
            actorMembershipVersion = Instant.parse("2026-08-30T01:02:03Z"),
            permissions = JsonObject().put("message_read", false),
            submittedAt = Instant.parse("2026-08-30T01:02:04Z"),
        )
        val ROLE = TeamRoleDefinition(
            id = COMMAND.roleId,
            teamId = COMMAND.teamId,
            name = "Restricted",
            colorHex = "#123ABC",
            priority = 30,
            mentionable = false,
            permissions = emptySet(),
            createdAt = OffsetDateTime.parse("2026-08-30T01:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-08-30T01:00:00Z"),
        )
    }
}
