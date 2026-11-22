package com.cowork.project.domain.calendar.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarRecurrenceExpanderTest :
    DescribeSpec({
        val seoul = ZoneId.of("Asia/Seoul")
        fun at(text: String, zone: ZoneId = seoul): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()
        fun series(
            start: String,
            recurrence: CalendarRecurrence? = null,
            exceptions: List<CalendarOccurrenceException> = emptyList(),
            zone: ZoneId = seoul,
            duration: Duration = Duration.ofHours(1),
        ) = at(start, zone).let { CalendarSeries(it, it.plus(duration), zone, recurrence, exceptions) }

        describe("반복 펼치기") {
            it("반복이 없으면 기간과 겹치는 발생분 하나만 돌려준다") {
                val event = series("2026-12-01T10:00")

                CalendarRecurrenceExpander.expand(event, at("2026-12-01T00:00"), at("2026-12-02T00:00"))
                    .map { it.startAt } shouldBe listOf(at("2026-12-01T10:00"))
                CalendarRecurrenceExpander.expand(event, at("2026-12-01T11:00"), at("2026-12-02T00:00"))
                    .shouldBeEmpty()
            }

            it("기간 시작 전에 시작해 기간 안에서 끝나는 발생분도 넣는다") {
                val event = series("2026-12-01T23:30")

                CalendarRecurrenceExpander.expand(event, at("2026-12-02T00:00"), at("2026-12-03T00:00"))
                    .map { it.startAt } shouldBe listOf(at("2026-12-01T23:30"))
            }

            it("DAILY는 INTERVAL 간격으로 COUNT 개만 만든다") {
                val event = series("2026-12-01T09:00", CalendarRecurrence(RecurrenceFrequency.DAILY, 2, count = 3))

                CalendarRecurrenceExpander.expand(event, at("2026-11-01T00:00"), at("2027-01-01T00:00"))
                    .map { it.startAt } shouldBe
                    listOf(at("2026-12-01T09:00"), at("2026-12-03T09:00"), at("2026-12-05T09:00"))
            }

            it("UNTIL은 그 시각에 시작하는 발생분까지 넣는다") {
                val event = series(
                    "2026-12-01T09:00",
                    CalendarRecurrence(RecurrenceFrequency.DAILY, until = at("2026-12-03T09:00")),
                )

                CalendarRecurrenceExpander.originalStarts(event).toList() shouldBe
                    listOf(at("2026-12-01T09:00"), at("2026-12-02T09:00"), at("2026-12-03T09:00"))
            }

            it("WEEKLY는 BYDAY의 요일마다 만들고 시작 전 요일은 건너뛴다") {
                val event = series(
                    "2026-12-02T09:00",
                    CalendarRecurrence(
                        RecurrenceFrequency.WEEKLY,
                        2,
                        setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                        count = 4,
                    ),
                )

                CalendarRecurrenceExpander.originalStarts(event).toList() shouldBe listOf(
                    at("2026-12-04T09:00"),
                    at("2026-12-14T09:00"),
                    at("2026-12-18T09:00"),
                    at("2026-12-28T09:00"),
                )
            }

            it("WEEKLY에 BYDAY가 없으면 시작 요일을 쓴다") {
                val event = series("2026-12-02T09:00", CalendarRecurrence(RecurrenceFrequency.WEEKLY, count = 2))

                CalendarRecurrenceExpander.originalStarts(event).toList() shouldBe
                    listOf(at("2026-12-02T09:00"), at("2026-12-09T09:00"))
            }

            it("MONTHLY는 그 날짜가 없는 달을 건너뛴다") {
                val event = series("2027-01-31T09:00", CalendarRecurrence(RecurrenceFrequency.MONTHLY, count = 3))

                CalendarRecurrenceExpander.originalStarts(event).toList() shouldBe
                    listOf(at("2027-01-31T09:00"), at("2027-03-31T09:00"), at("2027-05-31T09:00"))
            }

            it("일광 절약 시간이 바뀌어도 현지 시작 시각을 지킨다") {
                val newYork = ZoneId.of("America/New_York")
                val event = series(
                    "2027-03-13T09:00",
                    CalendarRecurrence(RecurrenceFrequency.DAILY, count = 2),
                    zone = newYork,
                )

                CalendarRecurrenceExpander.originalStarts(event).toList() shouldBe
                    listOf(at("2027-03-13T09:00", newYork), at("2027-03-14T09:00", newYork))
            }

            it("취소한 발생분은 빼고 수정한 발생분은 바꾼 값으로 돌려준다") {
                val event = series(
                    "2026-12-01T09:00",
                    CalendarRecurrence(RecurrenceFrequency.DAILY, count = 3),
                    listOf(
                        CalendarOccurrenceException(at("2026-12-02T09:00"), cancelled = true),
                        CalendarOccurrenceException(
                            at("2026-12-03T09:00"),
                            title = "바뀐 회의",
                            startAt = at("2026-12-03T15:00"),
                            endAt = at("2026-12-03T17:00"),
                        ),
                    ),
                )

                val occurrences =
                    CalendarRecurrenceExpander.expand(event, at("2026-12-01T00:00"), at("2026-12-04T00:00"))

                occurrences.map { it.startAt } shouldBe listOf(at("2026-12-01T09:00"), at("2026-12-03T15:00"))
                occurrences.last().title shouldBe "바뀐 회의"
                occurrences.last().endAt shouldBe at("2026-12-03T17:00")
                occurrences.last().originalStartAt shouldBe at("2026-12-03T09:00")
            }

            it("원래 시각이 기간 밖이어도 옮긴 시각이 기간 안이면 넣는다") {
                val event = series(
                    "2026-12-01T09:00",
                    CalendarRecurrence(RecurrenceFrequency.WEEKLY, count = 3),
                    listOf(
                        CalendarOccurrenceException(
                            at("2026-12-15T09:00"),
                            startAt = at("2026-12-10T09:00"),
                            endAt = at("2026-12-10T10:00"),
                        ),
                    ),
                )

                CalendarRecurrenceExpander.expand(event, at("2026-12-09T00:00"), at("2026-12-11T00:00"))
                    .map { it.originalStartAt } shouldBe listOf(at("2026-12-15T09:00"))
            }

            it("규칙이 만들지 않는 시각의 예외는 무시한다") {
                val event = series(
                    "2026-12-01T09:00",
                    CalendarRecurrence(RecurrenceFrequency.DAILY, count = 2),
                    listOf(
                        CalendarOccurrenceException(
                            at("2026-12-20T09:00"),
                            startAt = at("2026-12-01T12:00"),
                            endAt = at("2026-12-01T13:00"),
                        ),
                    ),
                )

                CalendarRecurrenceExpander.expand(event, at("2026-12-01T00:00"), at("2026-12-02T00:00"))
                    .map { it.startAt } shouldBe listOf(at("2026-12-01T09:00"))
            }

            it("끝이 없는 반복의 전체 기간은 끝이 없다") {
                val event = series("2026-12-01T09:00", CalendarRecurrence(RecurrenceFrequency.DAILY))

                CalendarRecurrenceExpander.bounds(event) shouldBe (at("2026-12-01T09:00") to null)
            }

            it("COUNT 반복의 전체 기간은 마지막 발생분의 종료 시각까지다") {
                val event = series("2026-12-01T09:00", CalendarRecurrence(RecurrenceFrequency.DAILY, count = 3))

                CalendarRecurrenceExpander.bounds(event) shouldBe (at("2026-12-01T09:00") to at("2026-12-03T10:00"))
            }
        }

        describe("반복 규칙 검증") {
            it("UNTIL과 COUNT를 함께 받지 않는다") {
                shouldThrow<IllegalArgumentException> {
                    CalendarRecurrence(RecurrenceFrequency.DAILY, until = Instant.EPOCH, count = 1)
                }
            }

            it("WEEKLY가 아니면 BYDAY를 받지 않는다") {
                shouldThrow<IllegalArgumentException> {
                    CalendarRecurrence(RecurrenceFrequency.DAILY, byDay = setOf(DayOfWeek.MONDAY))
                }
            }
        }

        describe("이후 모두 범위로 나누기") {
            it("COUNT 규칙은 앞과 뒤의 발생 수로 나누고 뒤쪽 예외는 버린다") {
                val keep = CalendarOccurrenceException(at("2026-12-01T09:00"), title = "첫 회의")
                val drop = CalendarOccurrenceException(at("2026-12-04T09:00"), cancelled = true)
                val event = series(
                    "2026-12-01T09:00",
                    CalendarRecurrence(RecurrenceFrequency.DAILY, count = 5),
                    listOf(keep, drop),
                )

                val split = CalendarSeriesSplitter.split(event, at("2026-12-03T09:00"))!!

                split.head.count shouldBe 2
                split.tail.count shouldBe 3
                split.headExceptions shouldBe listOf(keep)
            }

            it("UNTIL이나 끝이 없는 규칙은 앞쪽을 나누는 시각 직전에 끝낸다") {
                val event = series(
                    "2026-12-01T09:00",
                    CalendarRecurrence(RecurrenceFrequency.DAILY, until = at("2026-12-31T09:00")),
                )

                val split = CalendarSeriesSplitter.split(event, at("2026-12-03T09:00"))!!

                CalendarRecurrenceExpander.originalStarts(event.copy(recurrence = split.head)).toList() shouldBe
                    listOf(at("2026-12-01T09:00"), at("2026-12-02T09:00"))
                split.tail.until shouldBe at("2026-12-31T09:00")
            }

            it("첫 발생분에서 나누면 전체 범위로 처리하도록 null을 돌려준다") {
                val event = series("2026-12-01T09:00", CalendarRecurrence(RecurrenceFrequency.DAILY, count = 3))

                CalendarSeriesSplitter.split(event, at("2026-12-01T09:00")) shouldBe null
            }

            it("발생분이 아닌 시각에서는 나누지 않는다") {
                val event = series("2026-12-01T09:00", CalendarRecurrence(RecurrenceFrequency.DAILY, count = 3))

                shouldThrow<IllegalArgumentException> {
                    CalendarSeriesSplitter.split(event, at("2026-12-02T10:00"))
                }
            }
        }
    })
