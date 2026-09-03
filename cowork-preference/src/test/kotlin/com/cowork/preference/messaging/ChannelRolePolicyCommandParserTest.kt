package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelRolePolicyCommandParserTest {

    @Test
    fun `UPSERT preserves message_read and drops unknown permissions`() {
        val payload = command().put(
            "permissions",
            JsonObject().put("message_read", true).put("message_write", true),
        )

        val decision = ChannelRolePolicyCommandParser.parse(POLICY_KEY, payload.encode())

        assertTrue(decision is ChannelRolePolicyCommandDecision.Apply)
        val parsed = (decision as ChannelRolePolicyCommandDecision.Apply).command
        assertEquals(ChannelRolePolicyCommandType.UPSERT, parsed.commandType)
        assertEquals(true, parsed.permissions?.getBoolean("message_read"))
        assertEquals(setOf("message_read"), parsed.permissions?.fieldNames())
    }

    @Test
    fun `UPSERT defaults missing message_read to false`() {
        val payloads = listOf(
            command().put("permissions", JsonObject()),
            command().put("permissions", JsonObject().put("message_write", true)),
        )

        payloads.forEach { payload ->
            val decision = ChannelRolePolicyCommandParser.parse(POLICY_KEY, payload.encode())

            assertTrue(decision is ChannelRolePolicyCommandDecision.Apply)
            val permissions = (decision as ChannelRolePolicyCommandDecision.Apply).command.permissions
            assertEquals(false, permissions?.getBoolean("message_read"))
            assertEquals(setOf("message_read"), permissions?.fieldNames())
        }
    }

    @Test
    fun `DELETE accepts absent permissions`() {
        val payload = command().put("commandType", "DELETE")
        payload.remove("permissions")

        val decision = ChannelRolePolicyCommandParser.parse(POLICY_KEY, payload.encode())

        assertTrue(decision is ChannelRolePolicyCommandDecision.Apply)
        assertEquals(null, (decision as ChannelRolePolicyCommandDecision.Apply).command.permissions)
    }

    @Test
    fun `non-boolean message_read is rejected with a terminal envelope`() {
        val invalidPermissions = listOf(
            JsonObject().put("message_read", "false"),
            JsonObject().put("message_read", 0),
            JsonObject().put("message_read", null),
        )

        invalidPermissions.forEach { permissions ->
            val decision = ChannelRolePolicyCommandParser.parse(
                POLICY_KEY,
                command().put("permissions", permissions).encode(),
            )
            assertTrue(decision is ChannelRolePolicyCommandDecision.Reject)
        }
    }

    @Test
    fun `missing null and non-object permissions are rejected`() {
        val payloads = listOf(
            command().apply { remove("permissions") },
            command().put("permissions", null),
            command().put("permissions", "message_read"),
            command().put("permissions", 1),
            command().put("permissions", listOf(false)),
        )

        payloads.forEach { payload ->
            val decision = ChannelRolePolicyCommandParser.parse(POLICY_KEY, payload.encode())

            assertTrue(decision is ChannelRolePolicyCommandDecision.Reject)
        }
    }

    @Test
    fun `stable policy key mismatch is rejected`() {
        val decision = ChannelRolePolicyCommandParser.parse("policy:7:8:10", command().encode())

        assertTrue(decision is ChannelRolePolicyCommandDecision.Reject)
        assertTrue((decision as ChannelRolePolicyCommandDecision.Reject).reason.contains("record key"))
    }

    @Test
    fun `missing trustworthy envelope is quarantined`() {
        val decision = ChannelRolePolicyCommandParser.parse(POLICY_KEY, "{}")

        assertTrue(decision is ChannelRolePolicyCommandDecision.Quarantine)
    }

    private fun command(): JsonObject = JsonObject()
        .put("schemaVersion", 1)
        .put("operationId", "00000000-0000-0000-0000-000000000001")
        .put("idempotencyKey", "policy-upsert-1")
        .put("requestHash", "a".repeat(64))
        .put("commandType", "UPSERT")
        .put("teamId", 7L)
        .put("channelId", 8L)
        .put("roleId", 9L)
        .put("actorId", 10L)
        .put("actorMembershipVersion", "2026-08-30T01:02:03Z")
        .put("permissions", JsonObject().put("message_read", false))
        .put("submittedAt", "2026-08-30T01:02:04Z")

    private companion object {
        const val POLICY_KEY = "policy:7:8:9"
    }
}
