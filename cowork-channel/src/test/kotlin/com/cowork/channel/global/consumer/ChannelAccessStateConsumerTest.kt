package com.cowork.channel.global.consumer

import com.cowork.channel.global.policy.PermissionKeys
import io.mockk.mockk
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class ChannelAccessStateConsumerTest {
    private val objectMapper = jacksonObjectMapper()
    private val consumer = ChannelAccessStateConsumer(mockk(), mockk(), mockk(), objectMapper)

    @Test
    fun `판정 결과의 허용 권한과 version을 읽는다`() {
        val access = consumer.parse(record(payload(allowed = setOf("message_read", "message_send"))))
        assertEquals("11:7", access.accessKey)
        assertEquals(3L, access.teamId)
        assertEquals(setOf("message_read", "message_send"), access.allowedPermissions)
        assertTrue(access.canReadPrivate)
        assertEquals(Instant.parse("2026-11-25T00:00:01.000001Z"), access.sourceAuthorizationVersion)
        assertEquals(Instant.parse("2026-11-25T00:00:03.123456Z"), access.sourceOccurredAt)
    }

    @Test
    fun `활성 멤버가 아니라서 version이 없는 거부 기록을 읽는다`() {
        val access = consumer.parse(
            record(
                payload(
                    allowed = emptySet(),
                    readPrivate = false,
                    membershipVersion = "1970-01-01T00:00:00Z",
                    authorizationVersion = null,
                ),
            ),
        )
        assertEquals(emptySet<String>(), access.allowedPermissions)
        assertNull(access.sourceAuthorizationVersion)
    }

    @Test
    fun `계약을 어긴 기록은 거절한다`() {
        val invalid = listOf(
            record(payload(allowed = setOf("message_read"), authorizationVersion = null)),
            record(payload(authorizationVersion = "2026-11-25T00:00:05Z")),
            record(payload().also { it.remove("readPrivate") }),
            record(payload().also { (it["permissions"] as MutableMap<*, *>).remove("voice_log_read") }),
            record(payload(), key = "11:8"),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { consumer.parse(it) } }
    }

    private fun payload(
        allowed: Set<String> = setOf("message_read"),
        readPrivate: Boolean = true,
        membershipVersion: String = "2026-11-25T00:00:02Z",
        authorizationVersion: String? = "2026-11-25T00:00:01.000001Z",
    ): MutableMap<String, Any?> = mutableMapOf(
        "eventType" to "UPSERT",
        "channelId" to 11,
        "accountId" to 7,
        "teamId" to 3,
        "permissions" to PermissionKeys.CHANNEL_SCOPED.associateWithTo(mutableMapOf()) { it in allowed },
        "readPrivate" to readPrivate,
        "sourceMembershipVersion" to membershipVersion,
        "sourceAuthorizationVersion" to authorizationVersion,
        "occurredAt" to "2026-11-25T00:00:03.123456Z",
    )

    private fun record(payload: Map<String, Any?>, key: String = "11:7") =
        ConsumerRecord(Topics.PREFERENCE_CHANNEL_ACCESS_STATE, 0, 0L, key, objectMapper.writeValueAsString(payload))
}
