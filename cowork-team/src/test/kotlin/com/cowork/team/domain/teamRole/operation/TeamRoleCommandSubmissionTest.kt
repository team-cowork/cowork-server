package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamMember.entity.TeamMemberEventState
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.outbox.OutboxWriter
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

class TeamRoleCommandSubmissionTest {

    @Test
    fun `권한 set 순서와 무관하게 같은 request hash를 만든다`() {
        val mapper = jacksonObjectMapper()
        val submission = TeamRoleCommandSubmission(
            mockk(),
            mockk(),
            mockk(),
            TeamRoleOperationMapper(mapper),
            mapper,
        )
        val first = TeamRoleCommandRoleInput(permissions = linkedSetOf("MANAGE_ROLES", "MENTION_ROLE"))
        val reordered = TeamRoleCommandRoleInput(permissions = linkedSetOf("MENTION_ROLE", "MANAGE_ROLES"))

        assertEquals(
            submission.requestHash(TeamRoleCommandType.UPDATE, 3L, 7L, null, 9L, first),
            submission.requestHash(TeamRoleCommandType.UPDATE, 3L, 7L, null, 9L, reordered),
        )
    }

    @Test
    fun `actor scoped idempotency key를 정규화하고 별도 UUID operation으로 command를 접수한다`() {
        val operationRepository = mockk<TeamRoleCommandOperationRepository>()
        val memberStateRepository = mockk<TeamMemberEventStateRepository>()
        val outboxWriter = mockk<OutboxWriter>()
        val operationId = slot<String>()
        val normalizedKey = slot<String>()
        val requestHash = slot<String>()
        val command = slot<TeamRoleCommandPayload>()
        val memberVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        var validationCalled = false
        every { memberStateRepository.findByKeyForUpdate(3L, 7L) } answers {
            assertTrue(validationCalled)
            TeamMemberEventState(
                teamId = 3L,
                userId = 7L,
                role = TeamRole.OWNER,
                teamName = "Backend",
                deleted = false,
                stateOccurredAt = memberVersion,
            )
        }
        every {
            operationRepository.insertPendingIfAbsent(
                capture(operationId),
                capture(normalizedKey),
                3L,
                7L,
                "CREATE",
                capture(requestHash),
            )
        } returns 1
        every { operationRepository.findByActorAndIdempotencyKeyForUpdate(7L, "한글-key") } answers {
            TeamRoleCommandOperation(
                operationId = operationId.captured,
                idempotencyKey = normalizedKey.captured,
                teamId = 3L,
                actorId = 7L,
                commandType = TeamRoleCommandType.CREATE,
                requestHash = requestHash.captured,
            )
        }
        every {
            outboxWriter.enqueue(
                TeamRoleContractTopics.COMMAND,
                "3",
                capture(command),
            )
        } just runs
        val mapper = jacksonObjectMapper()
        val submission = TeamRoleCommandSubmission(
            operationRepository,
            memberStateRepository,
            outboxWriter,
            TeamRoleOperationMapper(mapper),
            mapper,
        )

        val response = submission.submit(
            idempotencyKey = "  한글-key  ",
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            actorId = 7L,
            role = TeamRoleCommandRoleInput(
                name = "Reviewer",
                colorHex = "#5865F2",
                priority = 10,
                mentionable = true,
                permissions = setOf("MANAGE_ROLES"),
            ),
        ) {
            validationCalled = true
        }

        assertEquals("한글-key", normalizedKey.captured)
        assertEquals("한글-key", command.captured.idempotencyKey)
        assertEquals(memberVersion, command.captured.actorMembershipVersion)
        assertEquals(response.operationId, command.captured.operationId)
        assertNotEquals(normalizedKey.captured, response.operationId)
        assertTrue(runCatching { UUID.fromString(response.operationId) }.isSuccess)
        assertTrue(validationCalled)
        verify(exactly = 1) {
            outboxWriter.enqueue(TeamRoleContractTopics.COMMAND, "3", any())
        }
    }

    @Test
    fun `같은 actor key identity 재요청은 현재 검증 없이 저장된 terminal operation을 반환한다`() {
        val operationRepository = mockk<TeamRoleCommandOperationRepository>()
        val memberStateRepository = mockk<TeamMemberEventStateRepository>()
        val outboxWriter = mockk<OutboxWriter>()
        val mapper = jacksonObjectMapper()
        val submission = TeamRoleCommandSubmission(
            operationRepository,
            memberStateRepository,
            outboxWriter,
            TeamRoleOperationMapper(mapper),
            mapper,
        )
        val role = TeamRoleCommandRoleInput(
            name = "Reviewer",
            colorHex = "#5865F2",
            priority = 10,
            mentionable = true,
            permissions = setOf("MANAGE_ROLES"),
        )
        val requestHash = submission.requestHash(TeamRoleCommandType.CREATE, 3L, 7L, null, null, role)
        val stored = TeamRoleCommandOperation(
            operationId = "12b91f08-a7b9-4c6d-b26a-23f717e72e1c",
            idempotencyKey = "request-1",
            teamId = 3L,
            actorId = 7L,
            commandType = TeamRoleCommandType.CREATE,
            requestHash = requestHash,
            status = TeamRoleOperationStatus.FAILED,
            errorCode = "ACTOR_MEMBERSHIP_CHANGED",
            errorMessage = "요청 이후 팀 멤버십 상태가 변경되었습니다.",
        )
        every {
            operationRepository.insertPendingIfAbsent(any(), "request-1", 3L, 7L, "CREATE", requestHash)
        } returns 0
        every { operationRepository.findByActorAndIdempotencyKeyForUpdate(7L, "request-1") } returns stored
        var validationCalled = false

        val response = submission.submit(
            idempotencyKey = "request-1",
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            actorId = 7L,
            role = role,
        ) {
            validationCalled = true
        }

        assertEquals(stored.operationId, response.operationId)
        assertEquals(TeamRoleOperationStatus.FAILED, response.status)
        assertEquals(stored.errorCode, response.errorCode)
        assertFalse(validationCalled)
        verify(exactly = 0) { memberStateRepository.findByKeyForUpdate(any(), any()) }
        verify(exactly = 0) { outboxWriter.enqueue(any(), any(), any()) }
    }

    @Test
    fun `같은 actor key를 다른 request identity에 재사용하면 현재 검증 전에 conflict로 거절한다`() {
        val operationRepository = mockk<TeamRoleCommandOperationRepository>()
        val memberStateRepository = mockk<TeamMemberEventStateRepository>()
        val outboxWriter = mockk<OutboxWriter>()
        val mapper = jacksonObjectMapper()
        val submission = TeamRoleCommandSubmission(
            operationRepository,
            memberStateRepository,
            outboxWriter,
            TeamRoleOperationMapper(mapper),
            mapper,
        )
        val stored = TeamRoleCommandOperation(
            operationId = "12b91f08-a7b9-4c6d-b26a-23f717e72e1c",
            idempotencyKey = "request-1",
            teamId = 3L,
            actorId = 7L,
            commandType = TeamRoleCommandType.DELETE,
            requestHash = "b".repeat(64),
        )
        every {
            operationRepository.insertPendingIfAbsent(any(), "request-1", 3L, 7L, "DELETE", any())
        } returns 0
        every { operationRepository.findByActorAndIdempotencyKeyForUpdate(7L, "request-1") } returns stored
        var validationCalled = false

        val error = assertThrows<ExpectedException> {
            submission.submit(
                idempotencyKey = "request-1",
                commandType = TeamRoleCommandType.DELETE,
                teamId = 3L,
                actorId = 7L,
                roleId = 9L,
            ) {
                validationCalled = true
            }
        }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertFalse(validationCalled)
        verify(exactly = 0) { memberStateRepository.findByKeyForUpdate(any(), any()) }
        verify(exactly = 0) { outboxWriter.enqueue(any(), any(), any()) }
    }
}
