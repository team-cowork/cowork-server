package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.due
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.sprint
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.view
import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemFieldValue
import com.cowork.project.domain.githubBoard.event.GithubProjectIteration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDate

class GithubBoardRoadmapTest :
    DescribeSpec({
        val start = GithubProjectField(id = "F_START", name = "Start date", dataType = "DATE")
        val target = GithubProjectField(id = "F_TARGET", name = "target date", dataType = "DATE")
        val title = GithubProjectField(id = "F_TITLE", name = "Title", dataType = "TITLE")

        fun dated(id: String, startDate: String? = null, targetDate: String? = null) = item(id).copy(
            fieldValues = listOfNotNull(
                startDate?.let { GithubProjectItemFieldValue(start.id, start.name, "DATE", date = it) },
                targetDate?.let { GithubProjectItemFieldValue(target.id, target.name, "DATE", date = it) },
            ),
        )

        fun day(value: String) = LocalDate.parse(value)

        describe("GithubBoardRoadmapBuilder.span은") {
            it("시작 field의 날짜부터 종료 field의 날짜까지를 구간으로 만든다") {
                GithubBoardRoadmapBuilder.span(dated("a", "2026-11-02", "2026-11-10"), start, target) shouldBe
                    GithubBoardSpan(day("2026-11-02"), day("2026-11-10"))
            }

            it("한쪽 날짜만 있으면 그 날짜 하루를 구간으로 만든다") {
                GithubBoardRoadmapBuilder.span(dated("a", startDate = "2026-11-02"), start, target) shouldBe
                    GithubBoardSpan(day("2026-11-02"), day("2026-11-02"))
                GithubBoardRoadmapBuilder.span(dated("a", targetDate = "2026-11-10"), start, target) shouldBe
                    GithubBoardSpan(day("2026-11-10"), day("2026-11-10"))
            }

            it("종료일이 시작일보다 앞이면 시작일 하루를 구간으로 만든다") {
                GithubBoardRoadmapBuilder.span(dated("a", "2026-11-10", "2026-11-02"), start, target) shouldBe
                    GithubBoardSpan(day("2026-11-10"), day("2026-11-10"))
            }

            it("두 날짜가 모두 없거나 읽을 수 없으면 구간이 없다") {
                GithubBoardRoadmapBuilder.span(dated("a"), start, target) shouldBe null
                GithubBoardRoadmapBuilder.span(dated("a", "not-a-date"), start, target) shouldBe null
            }

            it("iteration field는 시작일부터 기간의 마지막 날까지를 구간으로 만든다") {
                GithubBoardRoadmapBuilder.span(item("a", iteration = "it-3"), sprint, sprint) shouldBe
                    GithubBoardSpan(day("2026-11-15"), day("2026-11-21"))
            }

            it("시작 field와 종료 field가 다르면 시작 iteration의 시작일과 종료 field의 날짜를 쓴다") {
                val value = item("a", iteration = "it-3", due = "2026-11-30")

                GithubBoardRoadmapBuilder.span(value, sprint, due) shouldBe
                    GithubBoardSpan(day("2026-11-15"), day("2026-11-30"))
            }

            it("지워진 iteration은 값이 없는 것으로 본다") {
                GithubBoardRoadmapBuilder.span(item("a", iteration = "it-gone"), sprint, sprint) shouldBe null
            }
        }

        describe("GithubBoardRoadmapBuilder.iterationRange는") {
            it("종료일을 시작일에 기간을 더한 날의 하루 전으로 계산한다") {
                val field = sprint.copy(iterations = listOf(GithubProjectIteration("it-x", "Long", "2026-12-28", 14)))
                val value = item("a").copy(
                    fieldValues = listOf(
                        GithubProjectItemFieldValue(sprint.id, sprint.name, "ITERATION", iterationId = "it-x"),
                    ),
                )

                GithubBoardRoadmapBuilder.iterationRange(value, field) shouldBe (day("2026-12-28") to day("2027-01-10"))
            }

            it("기간이 0 이하이면 시작일 하루로 계산한다") {
                val field = sprint.copy(iterations = listOf(GithubProjectIteration("it-x", "Empty", "2026-12-28", 0)))
                val value = item("a").copy(
                    fieldValues = listOf(
                        GithubProjectItemFieldValue(sprint.id, sprint.name, "ITERATION", iterationId = "it-x"),
                    ),
                )

                GithubBoardRoadmapBuilder.iterationRange(value, field) shouldBe (day("2026-12-28") to day("2026-12-28"))
            }
        }

        describe("GithubBoardRoadmapBuilder.selectFields는") {
            it("이름이 맞는 date field를 대소문자를 무시하고 시작 field와 종료 field로 고른다") {
                val base = board(emptyList())
                val board = base.copy(fields = base.fields + target + start)

                val fields = GithubBoardRoadmapBuilder.selectFields(board, null, null)

                fields.start shouldBe start
                fields.end shouldBe target
                fields.source shouldBe GithubBoardRoadmapSettingsSource.DEFAULT
            }

            it("이름이 맞는 date field가 하나뿐이면 그 field를 양쪽에 쓴다") {
                val fields = GithubBoardRoadmapBuilder.selectFields(board(emptyList()), null, null)

                fields.start shouldBe due
                fields.end shouldBe due
            }

            it("이름이 맞는 field가 없으면 첫 iteration field를 양쪽에 쓴다") {
                val base = board(emptyList())
                val board = base.copy(fields = base.fields.filter { it.id != due.id })

                val fields = GithubBoardRoadmapBuilder.selectFields(board, null, null)

                fields.start shouldBe sprint
                fields.end shouldBe sprint
            }

            it("iteration field도 없으면 첫 date field를 양쪽에 쓴다") {
                val other = GithubProjectField(id = "F_OTHER", name = "Released", dataType = "DATE")
                val board = board(emptyList()).copy(fields = listOf(title, other))

                val fields = GithubBoardRoadmapBuilder.selectFields(board, null, null)

                fields.start shouldBe other
                fields.end shouldBe other
            }

            it("요청한 field를 쓰고 한쪽만 요청하면 다른 쪽은 기본 규칙을 따른다") {
                val board = board(emptyList())

                val both = GithubBoardRoadmapBuilder.selectFields(board, sprint.id, due.id)
                val onlyStart = GithubBoardRoadmapBuilder.selectFields(board, sprint.id, null)

                (both.start to both.end) shouldBe (sprint to due)
                both.source shouldBe GithubBoardRoadmapSettingsSource.REQUEST
                (onlyStart.start to onlyStart.end) shouldBe (sprint to due)
                onlyStart.source shouldBe GithubBoardRoadmapSettingsSource.REQUEST
            }

            it("date나 iteration이 아니거나 없는 field를 요청하면 거부한다") {
                val board = board(emptyList())

                shouldThrow<ExpectedException> { GithubBoardRoadmapBuilder.selectFields(board, "F_POINTS", null) }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
                shouldThrow<ExpectedException> { GithubBoardRoadmapBuilder.selectFields(board, null, "F_GONE") }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("쓸 수 있는 field가 없으면 충돌로 거부한다") {
                val board = board(emptyList()).copy(fields = listOf(title))

                shouldThrow<ExpectedException> { GithubBoardRoadmapBuilder.selectFields(board, null, null) }
                    .statusCode shouldBe HttpStatus.CONFLICT
            }
        }

        describe("GithubBoardRoadmapBuilder.build는") {
            it("일정이 없는 카드를 미배정 목록으로 빼고 나머지를 그룹으로 나눈다") {
                val roadmapView = view(layout = ROADMAP_LAYOUT, groupFieldIds = listOf("F_STATUS"))
                val board = board(
                    listOf(
                        item("a", statusOption = "opt-todo", due = "2026-11-20"),
                        item("b", statusOption = "opt-todo"),
                        item("c", due = "2026-11-25"),
                        item("d"),
                    ),
                    listOf(roadmapView),
                )
                val fields = GithubBoardRoadmapBuilder.selectFields(board, null, null)

                val roadmap = GithubBoardRoadmapBuilder.build(board, roadmapView, board.items, fields)

                roadmap.unscheduled.map { it.itemId } shouldContainExactly listOf("b", "d")
                roadmap.groups.map { group -> group.id to group.items.map { it.item.itemId } } shouldContainExactly
                    listOf(
                        null to listOf("c"),
                        "opt-todo" to listOf("a"),
                        "opt-doing" to emptyList(),
                        "opt-done" to emptyList(),
                    )
                roadmap.groups[1].items.single().span shouldBe GithubBoardSpan(day("2026-11-20"), day("2026-11-20"))
                roadmap.dateUnit shouldBe GithubBoardRoadmapDateUnit.MONTH
            }

            it("Roadmap 뷰가 아니면 거부한다") {
                val board = board(emptyList(), listOf(view(layout = TABLE_LAYOUT)))

                shouldThrow<ExpectedException> { GithubBoardRoadmapBuilder.requireRoadmapView(board, 1) }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
                shouldThrow<ExpectedException> { GithubBoardRoadmapBuilder.requireRoadmapView(board, 9) }
                    .statusCode shouldBe HttpStatus.NOT_FOUND
            }
        }
    })
