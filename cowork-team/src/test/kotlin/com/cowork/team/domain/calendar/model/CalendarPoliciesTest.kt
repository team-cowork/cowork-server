package com.cowork.team.domain.calendar.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class CalendarPoliciesTest :
    DescribeSpec({
        val start = Instant.parse("2026-12-01T00:00:00Z")
        val daily = CalendarSeries(
            start,
            start.plus(Duration.ofHours(1)),
            ZoneId.of("Asia/Seoul"),
            CalendarRecurrence(RecurrenceFrequency.DAILY, count = 3),
        )

        describe("권한 판정") {
            it("작성자는 팀 권한 없이 수정하고 삭제한다") {
                CalendarPermissionPolicy.allows(createdBy = 7, userId = 7) { false } shouldBe true
            }

            it("다른 사람의 일정은 팀 권한이 있어야 한다") {
                CalendarPermissionPolicy.allows(createdBy = 7, userId = 8) { false } shouldBe false
                CalendarPermissionPolicy.allows(createdBy = 7, userId = 8) { true } shouldBe true
            }
        }

        describe("리마인더 값") {
            it("중복을 없애고 정렬한다") {
                CalendarReminderPolicy.normalize(listOf(60, 10, 60)) shouldBe listOf(10, 60)
                CalendarReminderPolicy.normalize(null) shouldBe emptyList()
            }

            it("허용하지 않는 값이나 2개를 넘으면 거부한다") {
                CalendarReminderPolicy.normalize(listOf(5)) shouldBe null
                CalendarReminderPolicy.normalize(listOf(0, 10, 30)) shouldBe null
            }
        }

        describe("리마인더 받는 사람") {
            it("참석자가 있으면 참석자가 받는다") {
                CalendarReminderPolicy.recipients(1, listOf(3, 2, 3), setOf(1, 2, 3)) shouldBe listOf(2L, 3L)
            }

            it("참석자가 없으면 작성자가 받는다") {
                CalendarReminderPolicy.recipients(1, emptyList(), setOf(1)) shouldBe listOf(1L)
            }

            it("지금 팀 멤버가 아닌 사용자는 뺀다") {
                CalendarReminderPolicy.recipients(1, listOf(2, 3), setOf(3)) shouldBe listOf(3L)
                CalendarReminderPolicy.recipients(1, emptyList(), emptySet()).shouldBeEmpty()
            }
        }

        describe("보낼 리마인더") {
            it("직전 확인 시각 초과부터 지금까지 보낼 시각이 온 것만 찾는다") {
                val now = start.plus(Duration.ofDays(1)).minus(Duration.ofMinutes(10))

                val due = CalendarReminderPolicy.due(daily, listOf(10, 60), now.minusSeconds(60), now)

                due.map { it.occurrence.originalStartAt to it.minutesBefore } shouldBe
                    listOf(start.plus(Duration.ofDays(1)) to 10)
            }

            it("1일 전 리마인더는 하루 앞서 보낸다") {
                val now = start.plus(Duration.ofDays(1))

                val due = CalendarReminderPolicy.due(daily, listOf(1440), now.minusSeconds(60), now)

                due.map { it.occurrence.originalStartAt } shouldBe listOf(start.plus(Duration.ofDays(2)))
            }

            it("보낼 시각이 직전 확인 시각과 같으면 이미 처리한 것으로 본다") {
                val due = CalendarReminderPolicy.due(daily, listOf(0), start, start.plusSeconds(60))

                due.shouldBeEmpty()
            }
        }

        describe("조회 기간") {
            it("최대 93일까지 받는다") {
                CalendarRangePolicy.isValid(start, start.plus(Duration.ofDays(93))) shouldBe true
                CalendarRangePolicy.isValid(start, start.plus(Duration.ofDays(93)).plusSeconds(1)) shouldBe false
                CalendarRangePolicy.isValid(start, start) shouldBe false
            }
        }
    })
