package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandContractParserTest {

    @Test
    fun `상관 가능한 team role command 계약 오류는 terminal rejection으로 분류한다`() {
        val payload = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e")
            .put("idempotencyKey", "role-create-1")
            .put("requestHash", "a".repeat(64))
            .put("commandType", "CREATE")
            .put("teamId", 3)
            .put("actorId", 7)
            .put("actorMembershipVersion", "2026-08-27T01:00:00Z")
            .put("submittedAt", "2026-08-27T01:01:00Z")
            .put("role", JsonObject().put("name", "incomplete"))

        val decision = TeamRoleCommandParser.parse("3", payload.encode())

        assertEquals("Reject", decision::class.simpleName)
    }

    @Test
    fun `상관 가능한 GitHub setting command 계약 오류는 terminal rejection으로 분류한다`() {
        val payload = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e")
            .put("idempotencyKey", "github-policy-1")
            .put("commandType", "UPDATE")
            .put("repoId", 101)
            .put("requestedBy", 7)
            .put("occurredAt", "2026-08-27T01:01:00Z")
            .put("settings", JsonObject().put("label_auto_apply", "invalid"))

        val decision = GithubRepoSettingCommandParser.parse("101", payload.encode())

        assertEquals("Reject", decision::class.simpleName)
    }

    @Test
    fun `result envelope가 없는 team role garbage는 quarantine으로 분류한다`() {
        val payload = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e")
            .put("idempotencyKey", "role-create-1")
            .put("commandType", "CREATE")
            .put("teamId", 3)

        val decision = TeamRoleCommandParser.parse("3", payload.encode())

        assertEquals("Quarantine", decision::class.simpleName)
    }

    @Test
    fun `result envelope가 없는 GitHub setting garbage는 quarantine으로 분류한다`() {
        val payload = JsonObject()
            .put("schemaVersion", 1)
            .put("idempotencyKey", "github-policy-1")
            .put("commandType", "UPDATE")
            .put("repoId", 101)

        val decision = GithubRepoSettingCommandParser.parse("101", payload.encode())

        assertEquals("Quarantine", decision::class.simpleName)
    }
}
