package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.view
import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectViewSort
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly

class GithubBoardSorterTest :
    DescribeSpec({
        describe("GithubBoardSorter는") {
            fun sorted(sortBy: List<GithubProjectViewSort>, items: List<GithubProjectItemStateEvent>) =
                view(sortBy = sortBy).let { view -> GithubBoardSorter.sort(board(items, listOf(view)), view, items) }
                    .map { it.itemId }

            it("정렬이 없으면 입력 순서를 그대로 둔다") {
                sorted(emptyList(), listOf(item("b"), item("a"))) shouldContainExactly listOf("b", "a")
            }

            it("값이 없는 item은 방향과 상관없이 뒤에 둔다") {
                val items = listOf(item("a"), item("b", points = 1.0), item("c", points = 3.0))

                sorted(listOf(GithubProjectViewSort("F_POINTS", "ASC")), items) shouldContainExactly
                    listOf("b", "c", "a")
                sorted(listOf(GithubProjectViewSort("F_POINTS", "DESC")), items) shouldContainExactly
                    listOf("c", "b", "a")
            }

            it("single-select는 옵션 순서, iteration은 시작일 순서로 정렬한다") {
                val byStatus = listOf(item("a", statusOption = "opt-done"), item("b", statusOption = "opt-todo"))
                val bySprint = listOf(item("a", iteration = "it-4"), item("b", iteration = "it-2"))

                sorted(listOf(GithubProjectViewSort("F_STATUS", "ASC")), byStatus) shouldContainExactly listOf("b", "a")
                sorted(listOf(GithubProjectViewSort("F_SPRINT", "ASC")), bySprint) shouldContainExactly listOf("b", "a")
            }

            it("앞 정렬 값이 같으면 다음 정렬을 쓰고 모두 같으면 입력 순서를 지킨다") {
                val items = listOf(
                    item("a", statusOption = "opt-todo", due = "2026-12-01"),
                    item("b", statusOption = "opt-todo", due = "2026-11-01"),
                    item("c", statusOption = "opt-done"),
                    item("d", statusOption = "opt-todo", due = "2026-11-01"),
                )
                val sortBy = listOf(GithubProjectViewSort("F_STATUS", "ASC"), GithubProjectViewSort("F_DUE", "ASC"))

                sorted(sortBy, items) shouldContainExactly listOf("b", "d", "a", "c")
            }

            it("비교할 수 없는 field와 지워진 field의 정렬은 건너뛴다") {
                val items = listOf(item("a", points = 2.0), item("b", points = 1.0))
                val sortBy = listOf(
                    GithubProjectViewSort("F_TYPE", "ASC"),
                    GithubProjectViewSort("F_GONE", "ASC"),
                    GithubProjectViewSort("F_POINTS", "ASC"),
                )

                sorted(sortBy, items) shouldContainExactly listOf("b", "a")
            }

            it("제목은 대소문자를 무시하고 정렬한다") {
                val items = listOf(item("a", title = "beta"), item("b", title = "Alpha"))
                val title = GithubProjectField("F_TITLE", "Title", "TITLE")
                val view = view(sortBy = listOf(GithubProjectViewSort("F_TITLE", "ASC")))
                val base = board(items, listOf(view))
                val board = base.copy(fields = base.fields + title)

                GithubBoardSorter.sort(board, view, items).map { it.itemId } shouldContainExactly listOf("b", "a")
            }
        }
    })
