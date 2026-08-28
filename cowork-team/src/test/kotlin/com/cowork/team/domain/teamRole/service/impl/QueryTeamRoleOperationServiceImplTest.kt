package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandOperation
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandOperationRepository
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationMapper
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationProjectionFinalizer
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper

class QueryTeamRoleOperationServiceImplTest {

    private val operationRepository = mockk<TeamRoleCommandOperationRepository>()
    private val operationMapper = TeamRoleOperationMapper(jacksonObjectMapper())
    private val finalizer = mockk<TeamRoleOperationProjectionFinalizer>(relaxed = true)

    private val service = QueryTeamRoleOperationServiceImpl(
        operationRepository,
        operationMapper,
        finalizer,
    )

    @Test
    fun `조회한 operation의 teamId와 actorId가 모두 일치하면 finalize를 시도하고 응답을 반환한다`() {
        val teamId = 3L
        val actorId = 7L
        val operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e"
        val operation = TeamRoleCommandOperation(
            operationId = operationId,
            idempotencyKey = "role-create-1",
            teamId = teamId,
            actorId = actorId,
            commandType = TeamRoleCommandType.CREATE,
            requestHash = "a".repeat(64),
            status = TeamRoleOperationStatus.PROCESSING,
        )
        every { operationRepository.findByIdForUpdate(operationId) } returns operation

        val response = service.execute(actorId, teamId, operationId)

        assertEquals(operationId, response.operationId)
        assertEquals(TeamRoleOperationStatus.PROCESSING, response.status)
        verify(exactly = 1) { finalizer.tryFinalize(operation) }
    }

    @Test
    fun `operation이 존재하지 않으면 404를 던지고 finalize를 호출하지 않는다`() {
        val operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e"
        every { operationRepository.findByIdForUpdate(operationId) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(actorId = 7L, teamId = 3L, operationId = operationId)
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        verify(exactly = 0) { finalizer.tryFinalize(any()) }
    }

    @Test
    fun `operation의 teamId가 요청한 teamId와 다르면 404를 던진다`() {
        val operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e"
        val operation = TeamRoleCommandOperation(
            operationId = operationId,
            idempotencyKey = "role-create-1",
            teamId = 999L,
            actorId = 7L,
            commandType = TeamRoleCommandType.CREATE,
            requestHash = "a".repeat(64),
        )
        every { operationRepository.findByIdForUpdate(operationId) } returns operation

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(actorId = 7L, teamId = 3L, operationId = operationId)
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        verify(exactly = 0) { finalizer.tryFinalize(any()) }
    }

    @Test
    fun `operation의 actorId가 요청한 actorId와 다르면 다른 사용자의 operation을 볼 수 없도록 404를 던진다`() {
        val operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e"
        val operation = TeamRoleCommandOperation(
            operationId = operationId,
            idempotencyKey = "role-create-1",
            teamId = 3L,
            actorId = 999L,
            commandType = TeamRoleCommandType.CREATE,
            requestHash = "a".repeat(64),
        )
        every { operationRepository.findByIdForUpdate(operationId) } returns operation

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(actorId = 7L, teamId = 3L, operationId = operationId)
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        verify(exactly = 0) { finalizer.tryFinalize(any()) }
    }
}
