package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.global.outbox.OutboxWriter
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class ChannelRolePolicyCommandWireTest {
    @Test
    fun `command payload의 JSON wire key와 message_read boolean을 보존함`() {
        val objectMapper = jacksonObjectMapper()
        val writer = OutboxWriter(mockk<JdbcTemplate>(), objectMapper)
        val version = Instant.parse("2026-08-30T01:00:00.123456Z")
        val serialized = writer.serializePayload(
            ChannelRolePolicyCommandPayload(
                operationId = "019a0000-0000-7000-8000-000000000001",
                idempotencyKey = "key",
                requestHash = "a".repeat(64),
                commandType = ChannelRolePolicyCommandType.UPSERT,
                teamId = 10L,
                channelId = 3L,
                roleId = 5L,
                actorId = 7L,
                actorMembershipVersion = version,
                permissions = mapOf("message_read" to false),
                submittedAt = version,
            ),
        )
        val tree = objectMapper.readTree(serialized)

        assertEquals(1, tree.path("schemaVersion").asInt())
        assertEquals("UPSERT", tree.path("commandType").asText())
        assertEquals("2026-08-30T01:00:00.123456Z", tree.path("actorMembershipVersion").asText())
        assertFalse(tree.path("permissions").path("message_read").asBoolean())
    }
}
