package com.cowork.project.global.projection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ProjectionEventTimeTest {
    @Test
    fun `source LocalDateTime은 실행 환경 zone을 적용해 UTC Instant로 변환한다`() {
        val sourceTime = LocalDateTime.parse("2026-08-26T12:00:00.123456999")

        assertEquals(
            Instant.parse("2026-08-26T03:00:00.123456Z"),
            sourceTime.toProjectionSourceInstant(ZoneId.of("Asia/Seoul")),
        )
        assertEquals(
            Instant.parse("2026-08-26T12:00:00.123456Z"),
            sourceTime.toProjectionSourceInstant(ZoneOffset.UTC),
        )
    }
}
