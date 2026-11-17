package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.sprint
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.view
import com.cowork.project.domain.githubBoard.event.GithubProjectViewSort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubBoardKanbanTest :
    DescribeSpec({
        describe("GithubBoardKanbanBuilder는") {
            it("값이 없는 컬럼을 맨 앞에 두고 옵션 순서로 컬럼을 만들며 컬럼 안 순서는 입력 순서를 따른다") {
                val items = listOf(
                    item("a", statusOption = "opt-done"),
                    item("b"),
                    item("c", statusOption = "opt-todo"),
                    item("d", statusOption = "opt-done"),
                    item("e", statusOption = "deleted-option"),
                )
                val board = board(items)

                val kanban = GithubBoardKanbanBuilder.build(board, board.views.single(), board.items)

                kanban.columns.map { it.name } shouldContainExactly listOf("No Status", "Todo", "In Progress", "Done")
                kanban.columns.map { column -> column.items.map { it.itemId } } shouldContainExactly
                    listOf(listOf("b", "e"), listOf("c"), emptyList(), listOf("a", "d"))
                kanban.manualOrdering shouldBe true
            }

            it("iteration 컬럼은 시작일 순서이며 카드가 없는 지난 iteration은 숨긴다") {
                val board =
                    board(listOf(item("a", iteration = "it-1")), listOf(view(columnFieldIds = listOf(sprint.id))))

                val kanban = GithubBoardKanbanBuilder.build(board, board.views.single(), board.items)

                kanban.columns.map { it.id } shouldContainExactly listOf(null, "it-1", "it-3", "it-4")
            }

            it("뷰에 컬럼 field가 없으면 Status field를 쓴다") {
                val board = board(emptyList(), listOf(view(columnFieldIds = emptyList())))

                GithubBoardKanbanBuilder.columnField(board, board.views.single()).name shouldBe "Status"
            }

            it("Board 뷰가 아니면 거부한다") {
                val board = board(emptyList(), listOf(view(layout = "TABLE_LAYOUT")))

                shouldThrow<ExpectedException> { GithubBoardKanbanBuilder.requireBoardView(board, 1) }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("정렬이 있는 뷰는 직접 순서를 바꿀 수 없다고 알린다") {
                val sorted = view(sortBy = listOf(GithubProjectViewSort("F_POINTS", "DESC")))
                val board = board(listOf(item("a", points = 1.0), item("b", points = 5.0), item("c")), listOf(sorted))

                val ordered = GithubBoardSorter.sort(board, sorted, board.items)

                ordered.map { it.itemId } shouldContainExactly listOf("b", "a", "c")
                GithubBoardKanbanBuilder.build(board, sorted, ordered).manualOrdering shouldBe false
            }
        }

        describe("GithubBoardPositionResolver는") {
            val order = listOf("a", "b", "c", "d", "e")

            it("기준 카드가 있으면 그 카드 뒤로 옮긴다") {
                GithubBoardPositionResolver.resolve(order, listOf("b", "d"), "e", "d") shouldBe
                    GithubBoardPositionResolver.Placement.After("d")
            }

            it("컬럼 맨 위는 그 컬럼 첫 카드의 바로 앞 카드 뒤로 바꾼다") {
                GithubBoardPositionResolver.resolve(order, listOf("c", "e"), "e", null) shouldBe
                    GithubBoardPositionResolver.Placement.After("b")
                GithubBoardPositionResolver.resolve(order, listOf("a", "c"), "d", null) shouldBe
                    GithubBoardPositionResolver.Placement.After(null)
            }

            it("옮기는 카드는 순서 계산에서 뺀다") {
                GithubBoardPositionResolver.resolve(order, listOf("b", "c"), "a", null) shouldBe
                    GithubBoardPositionResolver.Placement.After(null)
            }

            it("빈 컬럼이면 위치를 바꾸지 않는다") {
                GithubBoardPositionResolver.resolve(order, listOf("a"), "a", null)
                    .shouldBeInstanceOf<GithubBoardPositionResolver.Placement.Unchanged>()
            }

            it("기준 카드가 컬럼에 없거나 옮기는 카드 자신이면 거부한다") {
                shouldThrow<ExpectedException> { GithubBoardPositionResolver.resolve(order, listOf("b"), "a", "c") }
                shouldThrow<ExpectedException> {
                    GithubBoardPositionResolver.resolve(order, listOf("a", "b"), "a", "a")
                }
            }
        }
    })
