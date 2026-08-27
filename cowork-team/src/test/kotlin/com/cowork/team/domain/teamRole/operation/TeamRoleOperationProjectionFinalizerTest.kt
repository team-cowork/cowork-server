package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamRole.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleProjection
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class TeamRoleOperationProjectionFinalizerTest {
    private val operationRepository = mockk<TeamRoleCommandOperationRepository>()
    private val roleRepository = mockk<TeamRoleProjectionRepository>()
    private val assignmentRepository = mockk<TeamRoleAssignmentProjectionRepository>()
    private val finalizer = TeamRoleOperationProjectionFinalizer(
        operationRepository,
        roleRepository,
        assignmentRepository,
        jacksonObjectMapper(),
    )

    @Test
    fun `기대한 role state version보다 새로운 후속 상태가 보여도 성공 처리한다`() {
        val expectedAt = Instant.parse("2026-08-27T01:02:03.123456Z")
        val operation = processingOperation(expectedAt)
        every { roleRepository.findById(9L) } returns Optional.of(
            TeamRoleProjection(
                roleId = 9L,
                teamId = 3L,
                sourceCreatedAt = expectedAt.minusSeconds(1),
                sourceOccurredAt = expectedAt.plusSeconds(1),
                deleted = true,
            ),
        )

        assertTrue(finalizer.tryFinalize(operation))
        assertEquals(TeamRoleOperationStatus.SUCCEEDED, operation.status)
    }

    @Test
    fun `local projection이 result version보다 오래되면 processing을 유지한다`() {
        val expectedAt = Instant.parse("2026-08-27T01:02:03.123456Z")
        val operation = processingOperation(expectedAt)
        every { roleRepository.findById(9L) } returns Optional.of(
            TeamRoleProjection(
                roleId = 9L,
                teamId = 3L,
                sourceCreatedAt = expectedAt.minusSeconds(2),
                sourceOccurredAt = expectedAt.minusSeconds(1),
            ),
        )

        assertFalse(finalizer.tryFinalize(operation))
        assertEquals(TeamRoleOperationStatus.PROCESSING, operation.status)
    }

    @Test
    fun `같은 version의 role state가 result 값과 다르면 processing을 유지한다`() {
        val expectedAt = Instant.parse("2026-08-27T01:02:03.123456Z")
        val operation = processingOperation(expectedAt)
        every { roleRepository.findById(9L) } returns Optional.of(
            TeamRoleProjection(
                roleId = 9L,
                teamId = 3L,
                name = "unexpected",
                sourceCreatedAt = expectedAt.minusSeconds(1),
                sourceOccurredAt = expectedAt,
            ),
        )

        assertFalse(finalizer.tryFinalize(operation))
        assertEquals(TeamRoleOperationStatus.PROCESSING, operation.status)
    }

    @Test
    fun `첫 batch가 완료되지 않아도 high watermark까지 다음 processing batch를 처리한다`() {
        val expectedAt = Instant.parse("2026-08-27T01:02:03.123456Z")
        val first = processingOperation(expectedAt, "10000000-0000-0000-0000-000000000000")
        val second = processingOperation(expectedAt, "f0000000-0000-0000-0000-000000000000")
        every {
            operationRepository.findTopByStatusOrderByOperationIdDesc(TeamRoleOperationStatus.PROCESSING)
        } returns second
        every {
            operationRepository.findStatusBatch(
                TeamRoleOperationStatus.PROCESSING,
                "",
                second.operationId,
                any<Pageable>(),
            )
        } returns listOf(first)
        every {
            operationRepository.findStatusBatch(
                TeamRoleOperationStatus.PROCESSING,
                first.operationId,
                second.operationId,
                any<Pageable>(),
            )
        } returns listOf(second)
        every { roleRepository.findById(9L) } returnsMany listOf(
            Optional.empty(),
            Optional.of(
                TeamRoleProjection(
                    roleId = 9L,
                    teamId = 3L,
                    name = "role",
                    sourceCreatedAt = expectedAt.minusSeconds(1),
                    sourceOccurredAt = expectedAt,
                ),
            ),
        )

        finalizer.finalizeVisibleOperations()

        assertEquals(TeamRoleOperationStatus.PROCESSING, first.status)
        assertEquals(TeamRoleOperationStatus.SUCCEEDED, second.status)
        verify(exactly = 2) {
            operationRepository.findStatusBatch(
                TeamRoleOperationStatus.PROCESSING,
                any(),
                second.operationId,
                any<Pageable>(),
            )
        }
    }

    private fun processingOperation(
        expectedAt: Instant,
        operationId: String = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e",
    ): TeamRoleCommandOperation = TeamRoleCommandOperation(
        operationId = operationId,
        idempotencyKey = "role-create-1",
        teamId = 3L,
        actorId = 7L,
        commandType = TeamRoleCommandType.CREATE,
        requestHash = "a".repeat(64),
    ).also {
        it.markProcessing(
            resultRoleJson = ROLE_JSON,
            expectation = TeamRoleProjectionExpectation("role:3:9", expectedAt, deleted = false),
        )
    }

    private companion object {
        val ROLE_JSON =
            """
            {
              "id": 9,
              "teamId": 3,
              "name": "role",
              "colorHex": "#000000",
              "priority": 0,
              "mentionable": false,
              "permissions": [],
              "createdAt": null,
              "updatedAt": null
            }
            """.trimIndent()
    }
}
