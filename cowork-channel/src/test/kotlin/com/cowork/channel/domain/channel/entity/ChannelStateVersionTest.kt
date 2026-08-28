package com.cowork.channel.domain.channel.entity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ChannelStateVersionTest :
    StringSpec({
        "clock rollback이면 저장된 microsecond보다 정확히 1 microsecond 큰 version을 선택한다" {
            val current = Instant.parse("2026-08-26T03:00:01.123456Z")
            val requested = Instant.parse("2026-08-26T03:00:00.999999999Z")

            nextChannelStateOccurredAt(current, requested) shouldBe
                Instant.parse("2026-08-26T03:00:01.123457Z")
        }

        "같은 DB microsecond의 요청도 다음 microsecond로 전진한다" {
            val current = Instant.parse("2026-08-26T03:00:01.123456Z")
            val requested = Instant.parse("2026-08-26T03:00:01.123456999Z")

            nextChannelStateOccurredAt(current, requested) shouldBe
                Instant.parse("2026-08-26T03:00:01.123457Z")
        }

        "더 최신 요청은 MySQL DATETIME(6) 정밀도로 정규화해 그대로 사용한다" {
            val current = Instant.parse("2026-08-26T03:00:01.123456Z")
            val requested = Instant.parse("2026-08-26T03:00:02.654321999Z")

            nextChannelStateOccurredAt(current, requested) shouldBe
                Instant.parse("2026-08-26T03:00:02.654321Z")
        }
    })
