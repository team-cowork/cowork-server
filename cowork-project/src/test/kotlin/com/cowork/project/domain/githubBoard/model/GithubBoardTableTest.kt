package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.view
import com.cowork.project.domain.githubBoard.event.GithubProjectViewSort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubBoardTableTest :
    DescribeSpec({
        describe("GithubBoardTableBuilder는") {
            it("뷰에 저장한 열 순서로 표시 field를 만들고 지워진 field는 뺀다") {
                val table = view(layout = TABLE_LAYOUT, visibleFieldIds = listOf("F_DUE", "F_GONE", "F_STATUS"))
                val board = board(emptyList(), listOf(table))

                GithubBoardTableBuilder.build(board, table, emptyList()).visibleFields.map {
                    it.id
                } shouldContainExactly
                    listOf("F_DUE", "F_STATUS")
            }

            it("뷰에 저장한 정렬을 field와 방향으로 돌려준다") {
                val table = view(
                    layout = TABLE_LAYOUT,
                    sortBy = listOf(GithubProjectViewSort("F_POINTS", "DESC"), GithubProjectViewSort("F_GONE", "ASC")),
                )
                val board = board(emptyList(), listOf(table))

                GithubBoardTableBuilder.build(board, table, emptyList()).sortBy.map {
                    it.field.id to it.direction
                } shouldContainExactly
                    listOf("F_POINTS" to "DESC")
            }

            it("정렬한 행을 그룹으로 나누고 그룹 안에서도 정렬 순서를 지킨다") {
                val table = view(
                    layout = TABLE_LAYOUT,
                    sortBy = listOf(GithubProjectViewSort("F_POINTS", "DESC")),
                    groupFieldIds = listOf("F_STATUS"),
                )
                val board = board(
                    listOf(
                        item("a", statusOption = "opt-todo", points = 1.0),
                        item("b", statusOption = "opt-todo", points = 3.0),
                        item("c", points = 2.0),
                    ),
                    listOf(table),
                )
                val rows = GithubBoardSorter.sort(board, table, board.items)

                val groups = GithubBoardTableBuilder.build(board, table, rows).grouping.groups

                groups.map { group -> group.items.map { it.itemId } } shouldContainExactly
                    listOf(listOf("c"), listOf("b", "a"), emptyList(), emptyList())
            }

            it("Table 뷰가 아니면 거부하고 없는 뷰는 찾을 수 없다고 알린다") {
                val board = board(emptyList(), listOf(view(layout = BOARD_LAYOUT)))

                shouldThrow<ExpectedException> { GithubBoardTableBuilder.requireTableView(board, 1) }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
                shouldThrow<ExpectedException> { GithubBoardTableBuilder.requireTableView(board, 2) }
                    .statusCode shouldBe HttpStatus.NOT_FOUND
            }
        }
    })
