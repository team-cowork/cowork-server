package com.cowork.project.global.outbox

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class OutboxWriterTest {
    @Test
    fun `payload를 JSON 객체 문자열로 직렬화한다`() {
        val writer = OutboxWriter(mockk<JdbcTemplate>(), jacksonObjectMapper())

        val serialized = writer.serializePayload(
            TimestampedPayload("DELETE", 42, Instant.parse("2026-08-26T03:00:00.123456Z")),
        )

        assertThat(serialized).isEqualTo(
            """{"eventType":"DELETE","entityId":42,"occurredAt":"2026-08-26T03:00:00.123456Z"}""",
        )
    }

    private data class TimestampedPayload(val eventType: String, val entityId: Int, val occurredAt: Instant)
}
