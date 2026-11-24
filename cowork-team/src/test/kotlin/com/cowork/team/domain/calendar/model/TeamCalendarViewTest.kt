package com.cowork.team.domain.calendar.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.time.ZoneId

class TeamCalendarViewTest :
    DescribeSpec({
        val zone = ZoneId.of("Asia/Seoul")
        val start = Instant.parse("2026-12-01T01:00:00Z")
        val from = Instant.parse("2026-12-01T00:00:00Z")
        val to = Instant.parse("2026-12-08T00:00:00Z")

        fun entry(
            source: CalendarSource,
            id: Long,
            projectId: Long? = null,
            categoryId: Long? = null,
            attendees: List<Long> = emptyList(),
            recurrence: CalendarRecurrence? = null,
            startAt: Instant = start,
        ) = CalendarEntry(
            source = source,
            eventId = id,
            projectId = projectId,
            createdBy = 1L,
            title = "event-$id",
            description = null,
            allDay = false,
            timeZone = zone.id,
            series = CalendarSeries(startAt, startAt.plusSeconds(3600), zone, recurrence),
            category = categoryId?.let { CalendarCategory(it, "c$it", "#000000") },
            attendeeUserIds = attendees,
            reminderMinutes = emptyList(),
        )

        val team = entry(CalendarSource.TEAM, 1L, attendees = listOf(7L))
        val publicProject =
            entry(CalendarSource.PROJECT, 1L, projectId = 10L, categoryId = 100L, attendees = listOf(8L))
        val privateProject = entry(CalendarSource.PROJECT, 2L, projectId = 20L, categoryId = 200L)
        val all = listOf(team, publicProject, privateProject)

        fun ids(items: List<CalendarItem>) = items.map { it.entry.source to it.entry.eventId }

        describe("가시성") {
            it("팀 일정은 모두 보고, 프로젝트 일정은 읽을 수 있는 프로젝트만 본다") {
                val items = TeamCalendarMerger.merge(all, setOf(10L), TeamCalendarFilter(), from, to)

                ids(items) shouldBe listOf(CalendarSource.TEAM to 1L, CalendarSource.PROJECT to 1L)
            }

            it("필터로 비공개 프로젝트를 골라도 읽을 수 없으면 넣지 않는다") {
                val items = TeamCalendarMerger.merge(
                    all,
                    setOf(10L),
                    TeamCalendarFilter(projectIds = setOf(20L)),
                    from,
                    to,
                )

                items.shouldBeEmpty()
            }
        }

        describe("필터") {
            val readable = setOf(10L, 20L)

            it("teamOnly는 팀 일정만, 프로젝트 ID와 함께 주면 둘 다 고른다") {
                ids(TeamCalendarMerger.merge(all, readable, TeamCalendarFilter(teamOnly = true), from, to)) shouldBe
                    listOf(CalendarSource.TEAM to 1L)
                ids(
                    TeamCalendarMerger.merge(
                        all,
                        readable,
                        TeamCalendarFilter(projectIds = setOf(20L), teamOnly = true),
                        from,
                        to,
                    ),
                ) shouldBe listOf(CalendarSource.TEAM to 1L, CalendarSource.PROJECT to 2L)
            }

            it("카테고리 필터를 주면 카테고리가 없는 팀 일정은 빠진다") {
                ids(
                    TeamCalendarMerger.merge(all, readable, TeamCalendarFilter(categoryIds = setOf(200L)), from, to),
                ) shouldBe
                    listOf(CalendarSource.PROJECT to 2L)
            }

            it("참석자 필터는 참석자 중 한 명이라도 맞으면 고른다") {
                ids(
                    TeamCalendarMerger.merge(
                        all,
                        readable,
                        TeamCalendarFilter(attendeeUserIds = setOf(7L, 8L)),
                        from,
                        to,
                    ),
                ) shouldBe listOf(CalendarSource.TEAM to 1L, CalendarSource.PROJECT to 1L)
            }

            it("필터끼리는 모두 만족해야 한다") {
                val filter = TeamCalendarFilter(projectIds = setOf(10L), teamOnly = true, attendeeUserIds = setOf(8L))

                ids(TeamCalendarMerger.merge(all, readable, filter, from, to)) shouldBe
                    listOf(CalendarSource.PROJECT to 1L)
                TeamCalendarMerger.merge(
                    all,
                    readable,
                    TeamCalendarFilter(teamOnly = true, categoryIds = setOf(100L)),
                    from,
                    to,
                ).shouldBeEmpty()
            }
        }

        describe("필터 값 읽기") {
            it("teamOnly와 양수 ID를 읽는다") {
                TeamCalendarFilter.of(listOf("teamOnly", " 3 "), listOf(5L), null) shouldBe
                    TeamCalendarFilter(projectIds = setOf(3L), teamOnly = true, categoryIds = setOf(5L))
                TeamCalendarFilter.of(null, null, null) shouldBe TeamCalendarFilter()
            }

            it("양수 ID나 teamOnly가 아니면 null이다") {
                TeamCalendarFilter.of(listOf("0"), null, null) shouldBe null
                TeamCalendarFilter.of(listOf("abc"), null, null) shouldBe null
                TeamCalendarFilter.of(listOf("teamonly"), null, null) shouldBe null
                TeamCalendarFilter.of(listOf("1"), null, null) shouldNotBe null
            }
        }

        describe("반복 펼치기와 정렬") {
            it("팀 일정과 프로젝트 일정의 반복을 기간 안에서 펼쳐 시작 시각 순으로 합친다") {
                val daily = entry(
                    CalendarSource.TEAM,
                    3L,
                    recurrence = CalendarRecurrence(RecurrenceFrequency.DAILY, count = 3),
                    startAt = Instant.parse("2026-12-02T00:00:00Z"),
                )
                val weekly = entry(
                    CalendarSource.PROJECT,
                    4L,
                    projectId = 10L,
                    recurrence = CalendarRecurrence(RecurrenceFrequency.WEEKLY),
                    startAt = Instant.parse("2026-11-26T02:00:00Z"),
                )

                val items = TeamCalendarMerger.merge(listOf(daily, weekly), setOf(10L), TeamCalendarFilter(), from, to)

                items.map { it.occurrence.startAt } shouldBe listOf(
                    Instant.parse("2026-12-02T00:00:00Z"),
                    Instant.parse("2026-12-03T00:00:00Z"),
                    Instant.parse("2026-12-03T02:00:00Z"),
                    Instant.parse("2026-12-04T00:00:00Z"),
                )
            }

            it("같은 시각이면 팀 일정을 먼저 둔다") {
                ids(
                    TeamCalendarMerger.merge(listOf(publicProject, team), setOf(10L), TeamCalendarFilter(), from, to),
                ) shouldBe
                    listOf(CalendarSource.TEAM to 1L, CalendarSource.PROJECT to 1L)
            }
        }
    })
