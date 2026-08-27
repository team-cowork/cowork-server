package com.cowork.team.domain.teamRole.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class TeamRoleCommandOperationTest {

    @Test
    fun `동일한 성공 result 중복만 기존 processing 상태를 유지한다`() {
        val operation = operation()
        val expectation = TeamRoleProjectionExpectation(
            key = "role:3:9",
            occurredAt = Instant.parse("2026-08-27T01:02:03.123456Z"),
            deleted = false,
        )

        operation.markProcessing(ROLE_JSON, expectation)
        operation.markProcessing(ROLE_JSON, expectation)

        assertEquals(TeamRoleOperationStatus.PROCESSING, operation.status)
        assertEquals(ROLE_JSON, operation.resultRoleJson)
        assertEquals(expectation.key, operation.expectedProjectionKey)
        assertEquals(expectation.occurredAt, operation.expectedProjectionOccurredAt)
    }

    @Test
    fun `processing operation의 상충하는 성공 또는 실패 result를 거부한다`() {
        val operation = operation()
        val expectation = TeamRoleProjectionExpectation(
            key = "role:3:9",
            occurredAt = Instant.parse("2026-08-27T01:02:03.123456Z"),
            deleted = false,
        )
        operation.markProcessing(ROLE_JSON, expectation)

        assertThrows(IllegalArgumentException::class.java) {
            operation.markProcessing(ROLE_JSON, expectation.copy(deleted = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            operation.markFailed("FORBIDDEN", "권한이 없습니다.")
        }
    }

    @Test
    fun `동일한 실패 result 중복만 terminal 상태에서 허용한다`() {
        val operation = operation()
        operation.markFailed("FORBIDDEN", "권한이 없습니다.")

        operation.markFailed("FORBIDDEN", "권한이 없습니다.")

        assertEquals(TeamRoleOperationStatus.FAILED, operation.status)
        assertThrows(IllegalArgumentException::class.java) {
            operation.markFailed("ROLE_NOT_FOUND", "역할이 없습니다.")
        }
    }

    private fun operation() = TeamRoleCommandOperation(
        operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e",
        idempotencyKey = "role-create-1",
        teamId = 3L,
        actorId = 7L,
        commandType = TeamRoleCommandType.CREATE,
        requestHash = "a".repeat(64),
    )

    private companion object {
        const val ROLE_JSON = "{\"id\":9,\"teamId\":3}"
    }
}
