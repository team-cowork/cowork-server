package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ChannelRolePolicyProjectionHandlerTest {
    private val repository = mockk<ChannelRolePolicyProjectionRepository>()
    private val handler = ChannelRolePolicyProjectionHandler(repository)

    @Test
    fun `같은 microsecond의 DELETE 이후 UPSERT는 policy를 되살리지 않음`() {
        val occurredAt = Instant.parse("2026-08-30T01:00:00.123456Z")
        var saved: ChannelRolePolicyProjection? = null
        every { repository.findById("policy:10:3:5") } answers { Optional.ofNullable(saved) }
        every { repository.save(any()) } answers { firstArg<ChannelRolePolicyProjection>().also { saved = it } }

        handler.handle(event("DELETE", occurredAt, null))
        handler.handle(event("UPSERT", occurredAt, mapOf("message_read" to true)))

        assertTrue(requireNotNull(saved).deleted)
    }

    private fun event(eventType: String, occurredAt: Instant, permissions: Map<String, Boolean>?) =
        ChannelRolePolicyChangedEvent(
            schemaVersion = 1,
            eventType = eventType,
            teamId = 10L,
            channelId = 3L,
            roleId = 5L,
            permissions = permissions,
            snapshot = false,
            occurredAt = occurredAt,
        )
}
