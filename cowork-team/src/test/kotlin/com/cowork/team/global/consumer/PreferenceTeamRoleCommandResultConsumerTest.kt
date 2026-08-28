package com.cowork.team.global.consumer

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandError
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandOperation
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandOperationRepository
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandResultPayload
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationProjectionFinalizer
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import com.cowork.team.domain.teamRole.operation.TeamRoleProjectionExpectation
import com.cowork.team.domain.teamRole.operation.TeamRoleResultRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class PreferenceTeamRoleCommandResultConsumerTest {

    private val operationRepository = mockk<TeamRoleCommandOperationRepository>()
    private val finalizer = mockk<TeamRoleOperationProjectionFinalizer>(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val consumer = PreferenceTeamRoleCommandResultConsumer(
        operationRepository,
        finalizer,
        objectMapper,
    )

    private val operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e"
    private val requestHash = "a".repeat(64)
    private val occurredAt = Instant.parse("2026-08-27T01:02:03.123456Z")

    private fun record(
        payload: TeamRoleCommandResultPayload,
        key: String = operationId,
    ): ConsumerRecord<String, String> =
        ConsumerRecord("preference.team-role.command-result", 0, 0, key, objectMapper.writeValueAsString(payload))

    private fun pendingOperation(
        commandType: TeamRoleCommandType = TeamRoleCommandType.CREATE,
        teamId: Long = 3L,
        idempotencyKey: String = "role-create-1",
    ): TeamRoleCommandOperation = TeamRoleCommandOperation(
        operationId = operationId,
        idempotencyKey = idempotencyKey,
        teamId = teamId,
        actorId = 7L,
        commandType = commandType,
        requestHash = requestHash,
    )

    private fun succeededPayload(
        commandType: TeamRoleCommandType = TeamRoleCommandType.CREATE,
        teamId: Long = 3L,
        idempotencyKey: String = "role-create-1",
        role: TeamRoleResultRole? = defaultRole(teamId),
        projectionKey: String = "role:3:9",
        deleted: Boolean = false,
    ): TeamRoleCommandResultPayload = TeamRoleCommandResultPayload(
        schemaVersion = 1,
        operationId = operationId,
        idempotencyKey = idempotencyKey,
        requestHash = requestHash,
        commandType = commandType,
        teamId = teamId,
        status = TeamRoleOperationStatus.SUCCEEDED,
        role = role,
        projection = TeamRoleProjectionExpectation(projectionKey, occurredAt, deleted),
        occurredAt = occurredAt,
    )

    private fun defaultRole(teamId: Long = 3L) = TeamRoleResultRole(
        id = 9L,
        teamId = teamId,
        name = "Reviewer",
        colorHex = "#5865F2",
        priority = 10,
        mentionable = true,
        permissions = setOf("MANAGE_ROLES"),
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `SUCCEEDED result는 operation을 processing으로 표시하고 finalize를 시도한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload()

        consumer.consume(record(payload))

        assertEquals(TeamRoleOperationStatus.PROCESSING, operation.status)
        assertEquals("role:3:9", operation.expectedProjectionKey)
        verify(exactly = 1) { finalizer.tryFinalize(operation) }
    }

    @Test
    fun `DELETE SUCCEEDED result는 role 없이 projection deleted true로 처리한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.DELETE)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(
            commandType = TeamRoleCommandType.DELETE,
            role = null,
            projectionKey = "role:3:9",
            deleted = true,
        )

        consumer.consume(record(payload))

        assertEquals(TeamRoleOperationStatus.PROCESSING, operation.status)
        verify(exactly = 1) { finalizer.tryFinalize(operation) }
    }

    @Test
    fun `ASSIGN SUCCEEDED result는 assignment projection key 접두어를 요구한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.ASSIGN)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(
            commandType = TeamRoleCommandType.ASSIGN,
            projectionKey = "assignment:3:7:9",
            deleted = false,
        )

        consumer.consume(record(payload))

        assertEquals(TeamRoleOperationStatus.PROCESSING, operation.status)
    }

    @Test
    fun `REVOKE SUCCEEDED result는 role과 함께 assignment projection deleted true를 요구한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.REVOKE)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(
            commandType = TeamRoleCommandType.REVOKE,
            projectionKey = "assignment:3:7:9",
            deleted = true,
        )

        consumer.consume(record(payload))

        assertEquals(TeamRoleOperationStatus.PROCESSING, operation.status)
    }

    @Test
    fun `REVOKE SUCCEEDED result에 role이 없으면 role 계약 위반으로 거절한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.REVOKE)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(
            commandType = TeamRoleCommandType.REVOKE,
            role = null,
            projectionKey = "assignment:3:7:9",
            deleted = true,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("REVOKE SUCCEEDED result의 role 계약이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `FAILED result는 operation을 failed로 표시하고 finalize를 호출하지 않는다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = TeamRoleCommandResultPayload(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "role-create-1",
            requestHash = requestHash,
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            status = TeamRoleOperationStatus.FAILED,
            error = TeamRoleCommandError(code = "VALIDATION_FAILED", message = "권한이 유효하지 않습니다."),
            occurredAt = occurredAt,
        )

        consumer.consume(record(payload))

        assertEquals(TeamRoleOperationStatus.FAILED, operation.status)
        assertEquals("VALIDATION_FAILED", operation.errorCode)
        verify(exactly = 0) { finalizer.tryFinalize(any()) }
    }

    @Test
    fun `유효하지 않은 JSON은 IllegalArgumentException을 던진다`() {
        val record = ConsumerRecord("preference.team-role.command-result", 0, 0, operationId, "not-json")

        assertThrows(IllegalArgumentException::class.java) { consumer.consume(record) }
    }

    @Test
    fun `지원하지 않는 schemaVersion은 거절한다`() {
        val payload = succeededPayload().copy(schemaVersion = 2)

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("지원하지 않는 팀 역할 command result schemaVersion입니다.", ex.message)
        verify(exactly = 0) { operationRepository.findByIdForUpdate(any()) }
    }

    @Test
    fun `operationId가 UUID가 아니면 거절한다`() {
        val payload = succeededPayload().copy(operationId = "not-a-uuid")

        val ex = assertThrows(IllegalArgumentException::class.java) {
            consumer.consume(record(payload, key = "not-a-uuid"))
        }

        assertEquals("팀 역할 command result operationId는 UUID여야 합니다.", ex.message)
    }

    @Test
    fun `idempotencyKey가 공백이면 거절한다`() {
        val payload = succeededPayload().copy(idempotencyKey = "   ")

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result idempotencyKey는 1~128자여야 합니다.", ex.message)
    }

    @Test
    fun `idempotencyKey가 128자를 초과하면 거절한다`() {
        val payload = succeededPayload().copy(idempotencyKey = "a".repeat(129))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result idempotencyKey는 1~128자여야 합니다.", ex.message)
    }

    @Test
    fun `requestHash가 정규식과 맞지 않으면 거절한다`() {
        val payload = succeededPayload().copy(requestHash = "not-a-hash")

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result requestHash가 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `record key가 payload operationId와 다르면 거절한다`() {
        val payload = succeededPayload()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            consumer.consume(record(payload, key = "다른-key-지만-uuid-아님"))
        }

        assertEquals("팀 역할 command result key가 operationId와 일치하지 않습니다.", ex.message)
    }

    @Test
    fun `terminal 상태가 아닌 PENDING status는 거절한다`() {
        val payload = succeededPayload().copy(status = TeamRoleOperationStatus.PENDING)

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result는 terminal 상태여야 합니다.", ex.message)
    }

    @Test
    fun `대응하는 operation이 없으면 거절한다`() {
        every { operationRepository.findByIdForUpdate(operationId) } returns null
        val payload = succeededPayload()

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result에 대응하는 operation이 없습니다: $operationId", ex.message)
    }

    @Test
    fun `operation의 teamId와 result teamId가 다르면 거절한다`() {
        val operation = pendingOperation(teamId = 3L)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(teamId = 999L, role = defaultRole(999L), projectionKey = "role:999:9")

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result의 teamId가 일치하지 않습니다.", ex.message)
    }

    @Test
    fun `operation의 commandType과 result commandType이 다르면 거절한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.UPDATE)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(commandType = TeamRoleCommandType.CREATE)

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result의 commandType이 일치하지 않습니다.", ex.message)
    }

    @Test
    fun `operation의 requestHash와 result requestHash가 다르면 거절한다`() {
        val operation = TeamRoleCommandOperation(
            operationId = operationId,
            idempotencyKey = "role-create-1",
            teamId = 3L,
            actorId = 7L,
            commandType = TeamRoleCommandType.CREATE,
            requestHash = "b".repeat(64),
        )
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload()

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result의 requestHash가 일치하지 않습니다.", ex.message)
    }

    @Test
    fun `operation의 idempotencyKey와 result idempotencyKey가 다르면 거절한다`() {
        val operation = pendingOperation(idempotencyKey = "다른-key")
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(idempotencyKey = "role-create-1")

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("팀 역할 command result의 idempotencyKey가 일치하지 않습니다.", ex.message)
    }

    @Test
    fun `FAILED result에 error가 없으면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = TeamRoleCommandResultPayload(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "role-create-1",
            requestHash = requestHash,
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            status = TeamRoleOperationStatus.FAILED,
            occurredAt = occurredAt,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("FAILED result에는 error가 필요합니다.", ex.message)
    }

    @Test
    fun `FAILED result에 role이 포함되면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = TeamRoleCommandResultPayload(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "role-create-1",
            requestHash = requestHash,
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            status = TeamRoleOperationStatus.FAILED,
            role = defaultRole(),
            error = TeamRoleCommandError("CODE", "메시지"),
            occurredAt = occurredAt,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("FAILED result에는 role 또는 projection이 포함될 수 없습니다.", ex.message)
    }

    @Test
    fun `FAILED result의 error code가 공백이면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = TeamRoleCommandResultPayload(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "role-create-1",
            requestHash = requestHash,
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            status = TeamRoleOperationStatus.FAILED,
            error = TeamRoleCommandError("", "메시지"),
            occurredAt = occurredAt,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("FAILED result error code는 1~100자여야 합니다.", ex.message)
    }

    @Test
    fun `FAILED result의 error message가 1000자를 초과하면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = TeamRoleCommandResultPayload(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "role-create-1",
            requestHash = requestHash,
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            status = TeamRoleOperationStatus.FAILED,
            error = TeamRoleCommandError("CODE", "a".repeat(1001)),
            occurredAt = occurredAt,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("FAILED result error message는 1~1000자여야 합니다.", ex.message)
    }

    @Test
    fun `SUCCEEDED result에 error가 포함되면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload().copy(error = TeamRoleCommandError("CODE", "메시지"))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("SUCCEEDED result에는 error가 포함될 수 없습니다.", ex.message)
    }

    @Test
    fun `SUCCEEDED result에 projection이 없으면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload().copy(projection = null)

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("SUCCEEDED result에는 projection expectation이 필요합니다.", ex.message)
    }

    @Test
    fun `projection key가 512자를 초과하면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(projectionKey = "role:3:" + "9".repeat(510))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("projection key는 1~512자여야 합니다.", ex.message)
    }

    @Test
    fun `projection occurredAt이 result occurredAt과 다르면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload().let {
            it.copy(projection = it.projection!!.copy(occurredAt = occurredAt.plusSeconds(1)))
        }

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("SUCCEEDED result의 occurredAt과 projection occurredAt이 일치해야 합니다.", ex.message)
    }

    @Test
    fun `CREATE SUCCEEDED result에 role이 없으면 role 계약 위반으로 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(role = null)

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("CREATE SUCCEEDED result의 role 계약이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `DELETE SUCCEEDED result에 role이 포함되면 role 계약 위반으로 거절한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.DELETE)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(
            commandType = TeamRoleCommandType.DELETE,
            role = defaultRole(),
            projectionKey = "role:3:9",
            deleted = true,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("DELETE SUCCEEDED result의 role 계약이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `result role의 teamId가 상위 teamId와 다르면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(role = defaultRole(teamId = 999L))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("result role 식별자가 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `result role의 id가 0 이하이면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(role = defaultRole().copy(id = 0L))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("result role 식별자가 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `result role 이름이 공백이면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(role = defaultRole().copy(name = "  "))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("result role 이름이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `result role 이름이 50자를 초과하면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(role = defaultRole().copy(name = "a".repeat(51)))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("result role 이름이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `result role 색상이 hex 형식이 아니면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(role = defaultRole().copy(colorHex = "blue"))

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("result role 색상이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `CREATE SUCCEEDED result의 projection deleted가 false가 아니면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(deleted = true)

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("CREATE SUCCEEDED result의 projection deleted 계약이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `REVOKE SUCCEEDED result의 projection deleted가 true가 아니면 거절한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.REVOKE)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(
            commandType = TeamRoleCommandType.REVOKE,
            projectionKey = "assignment:3:7:9",
            deleted = false,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("REVOKE SUCCEEDED result의 projection deleted 계약이 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `CREATE SUCCEEDED result의 projection key가 role 접두어가 아니면 거절한다`() {
        val operation = pendingOperation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(projectionKey = "assignment:3:9")

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("CREATE SUCCEEDED result의 projection key가 유효하지 않습니다.", ex.message)
    }

    @Test
    fun `ASSIGN SUCCEEDED result의 projection key가 assignment 접두어가 아니면 거절한다`() {
        val operation = pendingOperation(commandType = TeamRoleCommandType.ASSIGN)
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        val payload = succeededPayload(commandType = TeamRoleCommandType.ASSIGN, projectionKey = "role:3:9")

        val ex = assertThrows(IllegalArgumentException::class.java) { consumer.consume(record(payload)) }

        assertEquals("ASSIGN SUCCEEDED result의 projection key가 유효하지 않습니다.", ex.message)
    }
}
