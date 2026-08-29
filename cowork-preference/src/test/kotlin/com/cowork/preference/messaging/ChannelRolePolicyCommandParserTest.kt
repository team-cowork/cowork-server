package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelRolePolicyCommandParserTest {

    @Test
    fun `valid UPSERT accepts only the message_read boolean contract`() {
        val decision = ChannelRolePolicyCommandParser.parse(POLICY_KEY, command().encode())

        assertTrue(decision is ChannelRolePolicyCommandDecision.Apply)
        val parsed = (decision as ChannelRolePolicyCommandDecision.Apply).command
        assertEquals(ChannelRolePolicyCommandType.UPSERT, parsed.commandType)
        assertEquals(false, parsed.permissions?.getBoolean("message_read"))
        assertEquals(setOf("message_read"), parsed.permissions?.fieldNames())
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
    fun `empty unknown and non-boolean permissions are rejected with a terminal envelope`() {
        val invalidPermissions = listOf(
            JsonObject(),
            JsonObject().put("message_read", false).put("message_write", true),
            JsonObject().put("message_read", "false"),
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
