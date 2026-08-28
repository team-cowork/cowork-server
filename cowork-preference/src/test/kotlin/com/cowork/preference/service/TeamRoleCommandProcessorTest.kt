package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.messaging.TeamRoleCommand
import com.cowork.preference.messaging.TeamRoleCommandEnvelope
import com.cowork.preference.messaging.TeamRoleCommandQuarantineRecord
import com.cowork.preference.messaging.TeamRoleCommandRoleInput
import com.cowork.preference.messaging.TeamRoleCommandType
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProcessedTeamRoleCommand
import com.cowork.preference.repository.ProcessedTeamRoleCommandMatches
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleCommandInboxRepository
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class TeamRoleCommandProcessorTest {

    @Test
    fun `owner의 역할 생성은 authoritative mutation state result inbox를 한 transaction에 적재한다`() = runBlocking {
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val stateEvents = slot<Iterable<PreferenceEvent>>()
        val resultEvent = slot<PreferenceEvent>()
        val storedResult = slot<JsonObject>()
        val roleVersion = OffsetDateTime.parse("2026-08-27T01:02:03.123456Z")
        val role = TeamRoleDefinition(
            id = 9L,
            teamId = COMMAND.teamId,
            name = "Reviewer",
            colorHex = "#5865F2",
            priority = 10,
            mentionable = true,
            permissions = setOf("MANAGE_ROLES"),
            createdAt = roleVersion,
            updatedAt = roleVersion,
        )
        every { readiness.isReady } returns true
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, COMMAND) } returns noTeamRoleCommandMatches()
        coEvery { roleRepository.isTeamDeleted(connection, COMMAND.teamId) } returns false
        coEvery { memberRepository.find(connection, COMMAND.teamId, COMMAND.actorId) } returns TeamMemberProjection(
            teamId = COMMAND.teamId,
            accountId = COMMAND.actorId,
            builtInRole = "OWNER",
            deleted = false,
            sourceOccurredAt = COMMAND.actorMembershipVersion,
        )
        coEvery { roleRepository.findRoleByName(connection, COMMAND.teamId, "Reviewer") } returns null
        coEvery {
            roleRepository.insertRole(
                connection,
                COMMAND.teamId,
                "Reviewer",
                "#5865F2",
                10,
                true,
                setOf("MANAGE_ROLES"),
            )
        } returns role
        coEvery { outboxRepository.enqueueAll(connection, capture(stateEvents)) } returns Unit
        coEvery { outboxRepository.enqueue(connection, capture(resultEvent)) } returns Unit
        coEvery { inboxRepository.insert(connection, COMMAND, capture(storedResult)) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.process(COMMAND)

        val state = stateEvents.captured.single()
        assertEquals("preference.team-role.changed", state.topic)
        assertEquals("role:${COMMAND.teamId}:9", state.key)
        assertEquals("ROLE_UPSERTED", state.payload.getString("eventType"))
        assertEquals("preference.team-role.command-result", resultEvent.captured.topic)
        assertEquals("SUCCEEDED", storedResult.captured.getString("status"))
        assertEquals(state.key, storedResult.captured.getJsonObject("projection").getString("key"))
        assertEquals(state.occurredAt.toString(), storedResult.captured.getString("occurredAt"))
        coVerifyOrder {
            memberRepository.find(connection, COMMAND.teamId, COMMAND.actorId)
            roleRepository.findRoleByName(connection, COMMAND.teamId, "Reviewer")
            roleRepository.insertRole(connection, COMMAND.teamId, "Reviewer", "#5865F2", 10, true, any())
            outboxRepository.enqueueAll(connection, any())
            outboxRepository.enqueue(connection, any())
            inboxRepository.insert(connection, COMMAND, any())
        }
    }

    @Test
    fun `actor projection이 command version보다 뒤처지면 terminal result 없이 재시도한다`() = runBlocking {
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        every { readiness.isReady } returns true
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, COMMAND) } returns noTeamRoleCommandMatches()
        coEvery { roleRepository.isTeamDeleted(connection, COMMAND.teamId) } returns false
        coEvery { memberRepository.find(connection, COMMAND.teamId, COMMAND.actorId) } returns TeamMemberProjection(
            teamId = COMMAND.teamId,
            accountId = COMMAND.actorId,
            builtInRole = "OWNER",
            deleted = false,
            sourceOccurredAt = COMMAND.actorMembershipVersion.minusSeconds(1),
        )
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        val error = runCatching { processor.process(COMMAND) }.exceptionOrNull()

        assertTrue(error is TeamRoleProjectionNotReadyException)
        coVerify(exactly = 0) { outboxRepository.enqueueAll(any(), any()) }
        coVerify(exactly = 0) { inboxRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `actor projection이 command version보다 앞서면 membership changed로 종료한다`() = runBlocking {
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val storedResult = slot<JsonObject>()
        every { readiness.isReady } returns true
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, COMMAND) } returns noTeamRoleCommandMatches()
        coEvery { roleRepository.isTeamDeleted(connection, COMMAND.teamId) } returns false
        coEvery { memberRepository.find(connection, COMMAND.teamId, COMMAND.actorId) } returns TeamMemberProjection(
            teamId = COMMAND.teamId,
            accountId = COMMAND.actorId,
            builtInRole = "OWNER",
            deleted = false,
            sourceOccurredAt = COMMAND.actorMembershipVersion.plusSeconds(1),
        )
        coEvery { outboxRepository.enqueueAll(connection, any()) } returns Unit
        coEvery { outboxRepository.enqueue(connection, any()) } returns Unit
        coEvery { inboxRepository.insert(connection, COMMAND, capture(storedResult)) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.process(COMMAND)

        assertEquals("FAILED", storedResult.captured.getString("status"))
        assertEquals("ACTOR_MEMBERSHIP_CHANGED", storedResult.captured.getJsonObject("error").getString("code"))
        coVerify(exactly = 0) { roleRepository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `target projection이 command version보다 앞서면 assignment를 쓰지 않고 종료한다`() = runBlocking {
        val command = ASSIGN_COMMAND
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val storedResult = slot<JsonObject>()
        every { readiness.isReady } returns true
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, command) } returns noTeamRoleCommandMatches()
        coEvery { roleRepository.isTeamDeleted(connection, command.teamId) } returns false
        coEvery { memberRepository.find(connection, command.teamId, command.actorId) } returns member(
            command.actorId,
            command.actorMembershipVersion,
            "OWNER",
        )
        coEvery { memberRepository.find(connection, command.teamId, TARGET_ID) } returns member(
            TARGET_ID,
            requireNotNull(command.targetMembershipVersion).plusSeconds(1),
        )
        coEvery { outboxRepository.enqueueAll(connection, any()) } returns Unit
        coEvery { outboxRepository.enqueue(connection, any()) } returns Unit
        coEvery { inboxRepository.insert(connection, command, capture(storedResult)) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.process(command)

        assertEquals("FAILED", storedResult.captured.getString("status"))
        assertEquals("TARGET_MEMBERSHIP_CHANGED", storedResult.captured.getJsonObject("error").getString("code"))
        coVerify(exactly = 0) { roleRepository.assignRole(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `assignment는 검증한 target membership version에 귀속된다`() = runBlocking {
        val command = ASSIGN_COMMAND
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val stateEvents = slot<Iterable<PreferenceEvent>>()
        val assignmentVersion = OffsetDateTime.parse("2026-08-27T01:02:03.123456Z")
        val role = TeamRoleDefinition(
            id = requireNotNull(command.roleId),
            teamId = command.teamId,
            name = "Reviewer",
            colorHex = "#5865F2",
            priority = 10,
            mentionable = true,
            permissions = emptySet(),
            createdAt = assignmentVersion,
            updatedAt = assignmentVersion,
        )
        val assignment = AccountTeamRole(
            accountId = TARGET_ID,
            teamId = command.teamId,
            roleId = role.id,
            sourceMembershipVersion = command.targetMembershipVersion,
            updatedAt = assignmentVersion,
        )
        every { readiness.isReady } returns true
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, command) } returns noTeamRoleCommandMatches()
        coEvery { roleRepository.isTeamDeleted(connection, command.teamId) } returns false
        coEvery { memberRepository.find(connection, command.teamId, command.actorId) } returns member(
            command.actorId,
            command.actorMembershipVersion,
            "OWNER",
        )
        coEvery { memberRepository.find(connection, command.teamId, TARGET_ID) } returns member(
            TARGET_ID,
            requireNotNull(command.targetMembershipVersion),
        )
        coEvery { roleRepository.findRole(connection, command.teamId, role.id) } returns role
        coEvery {
            roleRepository.assignRole(
                connection,
                TARGET_ID,
                command.teamId,
                role.id,
                requireNotNull(command.targetMembershipVersion),
            )
        } returns role
        coEvery { roleRepository.findAssignment(connection, TARGET_ID, command.teamId, role.id) } returns assignment
        coEvery { outboxRepository.enqueueAll(connection, capture(stateEvents)) } returns Unit
        coEvery { outboxRepository.enqueue(connection, any()) } returns Unit
        coEvery { inboxRepository.insert(connection, command, any()) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.process(command)

        assertEquals("ASSIGNMENT_UPSERTED", stateEvents.captured.single().payload.getString("eventType"))
        coVerify(exactly = 1) {
            roleRepository.assignRole(
                connection,
                TARGET_ID,
                command.teamId,
                role.id,
                requireNotNull(command.targetMembershipVersion),
            )
        }
    }

    @Test
    fun `team tombstone 이후 queued command는 역할을 재생성하지 않고 종료한다`() = runBlocking {
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val storedResult = slot<JsonObject>()
        every { readiness.isReady } returns true
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, COMMAND) } returns noTeamRoleCommandMatches()
        coEvery { roleRepository.isTeamDeleted(connection, COMMAND.teamId) } returns true
        coEvery { outboxRepository.enqueueAll(connection, any()) } returns Unit
        coEvery { outboxRepository.enqueue(connection, any()) } returns Unit
        coEvery { inboxRepository.insert(connection, COMMAND, capture(storedResult)) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.process(COMMAND)

        assertEquals("FAILED", storedResult.captured.getString("status"))
        assertEquals("TEAM_DELETED", storedResult.captured.getJsonObject("error").getString("code"))
        coVerify(exactly = 0) { memberRepository.find(any(), any(), any()) }
        coVerify(exactly = 0) { roleRepository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `같은 actor와 idempotency key의 동일 요청은 새 operationId에도 mutation 없이 결과를 재생한다`() = runBlocking {
        val replayCommand = COMMAND.copy(operationId = "e8b784d4-24ca-45aa-8aab-1ae2a2bc6a6a")
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val storedResult = JsonObject()
            .put("operationId", COMMAND.operationId)
            .put("status", "SUCCEEDED")
            .put("occurredAt", "2026-08-27T01:02:03.123456Z")
        val existing = processedCommand(COMMAND, storedResult)
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, replayCommand) } returns
            ProcessedTeamRoleCommandMatches(null, existing)
        val resultEvent = slot<PreferenceEvent>()
        coEvery { outboxRepository.enqueue(connection, capture(resultEvent)) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.process(replayCommand)

        assertEquals(replayCommand.operationId, resultEvent.captured.key)
        assertEquals(replayCommand.operationId, resultEvent.captured.payload.getString("operationId"))
        assertEquals(COMMAND.operationId, storedResult.getString("operationId"))
        coVerify(exactly = 0) { roleRepository.isTeamDeleted(any(), any()) }
        coVerify(exactly = 0) { inboxRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `같은 actor와 idempotency key의 다른 요청은 mutation 없이 failed result로 종료한다`() = runBlocking {
        val conflictingCommand = COMMAND.copy(
            operationId = "e8b784d4-24ca-45aa-8aab-1ae2a2bc6a6a",
            requestHash = "b".repeat(64),
        )
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val existing = processedCommand(
            COMMAND,
            JsonObject().put("occurredAt", "2026-08-27T01:02:03.123456Z"),
        )
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.findMatches(connection, conflictingCommand) } returns
            ProcessedTeamRoleCommandMatches(null, existing)
        val resultEvent = slot<PreferenceEvent>()
        coEvery { outboxRepository.enqueue(connection, capture(resultEvent)) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.process(conflictingCommand)

        assertEquals("FAILED", resultEvent.captured.payload.getString("status"))
        assertEquals(
            "IDEMPOTENCY_KEY_REUSED",
            resultEvent.captured.payload.getJsonObject("error").getString("code"),
        )
        coVerify(exactly = 0) { roleRepository.isTeamDeleted(any(), any()) }
        coVerify(exactly = 0) { inboxRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `상관 가능한 malformed command는 quarantine과 failed result를 한 transaction에 적재한다`() = runBlocking {
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val envelope = TeamRoleCommandEnvelope(
            COMMAND.operationId,
            COMMAND.idempotencyKey,
            COMMAND.requestHash,
            COMMAND.commandType,
            COMMAND.teamId,
        )
        val quarantine = TeamRoleCommandQuarantineRecord(
            topic = "preference.team-role.command",
            partition = 0,
            offset = 12,
            key = COMMAND.teamId.toString(),
            payload = "{}",
            reason = "role is incomplete",
        )
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { inboxRepository.quarantine(connection, quarantine) } returns true
        val resultEvent = slot<PreferenceEvent>()
        coEvery { outboxRepository.enqueue(connection, capture(resultEvent)) } returns Unit
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        processor.rejectInvalid(envelope, quarantine, quarantine.reason)

        assertEquals("FAILED", resultEvent.captured.payload.getString("status"))
        assertEquals("INVALID_COMMAND", resultEvent.captured.payload.getJsonObject("error").getString("code"))
        coVerifyOrder {
            inboxRepository.quarantine(connection, quarantine)
            outboxRepository.enqueue(connection, any())
        }
    }

    private fun member(accountId: Long, version: Instant, role: String = "MEMBER") = TeamMemberProjection(
        teamId = COMMAND.teamId,
        accountId = accountId,
        builtInRole = role,
        deleted = false,
        sourceOccurredAt = version,
    )

    private fun noTeamRoleCommandMatches() = ProcessedTeamRoleCommandMatches(
        byOperationId = null,
        byActorAndIdempotencyKey = null,
    )

    private fun processedCommand(command: TeamRoleCommand, result: JsonObject) = ProcessedTeamRoleCommand(
        operationId = command.operationId,
        actorId = command.actorId,
        idempotencyKey = command.idempotencyKey,
        requestHash = command.requestHash,
        commandType = command.commandType.name,
        teamId = command.teamId,
        result = result,
    )

    private companion object {
        val COMMAND = TeamRoleCommand(
            operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e",
            idempotencyKey = "role-create-1",
            requestHash = "a".repeat(64),
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            actorId = 7L,
            actorMembershipVersion = Instant.parse("2026-08-27T01:00:00Z"),
            targetAccountId = null,
            targetMembershipVersion = null,
            roleId = null,
            role = TeamRoleCommandRoleInput(
                name = "Reviewer",
                colorHex = "#5865F2",
                priority = 10,
                mentionable = true,
                permissions = setOf("MANAGE_ROLES"),
            ),
            submittedAt = Instant.parse("2026-08-27T01:01:00Z"),
        )
        const val TARGET_ID = 11L
        val ASSIGN_COMMAND = COMMAND.copy(
            operationId = "a01769e0-9b8e-4c8f-a006-3bc76d5ea874",
            idempotencyKey = "role-assign-1",
            commandType = TeamRoleCommandType.ASSIGN,
            targetAccountId = TARGET_ID,
            targetMembershipVersion = Instant.parse("2026-08-27T01:00:30Z"),
            roleId = 9L,
            role = null,
        )
    }
}
