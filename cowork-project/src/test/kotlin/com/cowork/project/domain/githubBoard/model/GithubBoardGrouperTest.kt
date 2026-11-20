package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.view
import com.cowork.project.domain.githubBoard.event.GithubProjectField
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class GithubBoardGrouperTest :
    DescribeSpec({
        describe("GithubBoardGrouper는") {
            it("그룹 field가 없으면 모든 item을 입력 순서대로 그룹 하나에 담는다") {
                val board = board(listOf(item("b"), item("a")), listOf(view()))

                val grouping = GithubBoardGrouper.group(board, board.views.single(), board.items)

                grouping.field shouldBe null
                grouping.supported shouldBe true
                grouping.groups.map { it.id } shouldContainExactly listOf(null)
                grouping.groups.single().items.map { it.itemId } shouldContainExactly listOf("b", "a")
            }

            it("single-select는 값 없는 그룹을 맨 앞에 두고 옵션 순서를 따르며 그룹 안은 입력 순서를 따른다") {
                val board = board(
                    listOf(
                        item("a", statusOption = "opt-done"),
                        item("b"),
                        item("c", statusOption = "opt-todo"),
                        item("d", statusOption = "opt-done"),
                        item("e", statusOption = "deleted-option"),
                    ),
                    listOf(view(groupFieldIds = listOf("F_STATUS"))),
                )

                val grouping = GithubBoardGrouper.group(board, board.views.single(), board.items)

                grouping.groups.map { it.name } shouldContainExactly listOf("No Status", "Todo", "In Progress", "Done")
                grouping.groups.map { it.color } shouldContainExactly listOf(null, "GRAY", "YELLOW", "GREEN")
                grouping.groups.map { group -> group.items.map { it.itemId } } shouldContainExactly
                    listOf(listOf("b", "e"), listOf("c"), emptyList(), listOf("a", "d"))
            }

            it("iteration은 시작일 순서이며 카드가 없는 지난 iteration은 숨긴다") {
                val board = board(
                    listOf(item("a", iteration = "it-1"), item("b", iteration = "it-4")),
                    listOf(view(groupFieldIds = listOf("F_SPRINT"))),
                )

                val grouping = GithubBoardGrouper.group(board, board.views.single(), board.items)

                grouping.groups.map { it.id } shouldContainExactly listOf(null, "it-1", "it-3", "it-4")
            }

            it("number는 숫자 오름차순으로 나누고 정수는 소수점 없이 이름을 붙인다") {
                val board = board(
                    listOf(item("a", points = 10.0), item("b", points = 2.0), item("c", points = 2.5), item("d")),
                    listOf(view(groupFieldIds = listOf("F_POINTS"))),
                )

                val grouping = GithubBoardGrouper.group(board, board.views.single(), board.items)

                grouping.groups.map { it.name } shouldContainExactly listOf("No Story Points", "2", "2.5", "10")
                grouping.groups.first().items.map { it.itemId } shouldContainExactly listOf("d")
            }

            it("date는 날짜 오름차순으로 나눈다") {
                val board = board(
                    listOf(item("a", due = "2026-12-01"), item("b", due = "2026-11-20"), item("c", due = "2026-12-01")),
                    listOf(view(groupFieldIds = listOf("F_DUE"))),
                )

                val grouping = GithubBoardGrouper.group(board, board.views.single(), board.items)

                grouping.groups.map { it.id } shouldContainExactly listOf(null, "2026-11-20", "2026-12-01")
                grouping.groups.last().items.map { it.itemId } shouldContainExactly listOf("a", "c")
            }

            it("저장소와 마일스톤은 item 내용의 값으로 나누고 대소문자를 무시한 오름차순이다") {
                val repository = GithubProjectField(id = "F_REPO", name = "Repository", dataType = "REPOSITORY")
                val milestone = GithubProjectField(id = "F_MS", name = "Milestone", dataType = "MILESTONE")
                val items = listOf(
                    item("a", repository = "my-org/web", milestone = "v2"),
                    item("b", repository = "my-org/App", milestone = null),
                    item("c", repository = null, milestone = "V1"),
                )
                val byRepo = board(items, listOf(view(groupFieldIds = listOf("F_REPO"))))
                    .let { it.copy(fields = it.fields + repository + milestone) }
                val byMilestone = byRepo.copy(views = listOf(view(groupFieldIds = listOf("F_MS"))))

                GithubBoardGrouper.group(byRepo, byRepo.views.single(), items).groups.map {
                    it.name
                } shouldContainExactly
                    listOf("No Repository", "my-org/App", "my-org/web")
                GithubBoardGrouper.group(byMilestone, byMilestone.views.single(), items).groups
                    .map { group -> group.name to group.items.map { it.itemId } } shouldContainExactly
                    listOf("No Milestone" to listOf("b"), "V1" to listOf("c"), "v2" to listOf("a"))
            }

            it("값이 여러 개인 field는 그룹으로 나누지 않고 지원하지 않는다고 알린다") {
                val assignees = GithubProjectField(id = "F_ASSIGNEES", name = "Assignees", dataType = "ASSIGNEES")
                val base = board(listOf(item("a"), item("b")), listOf(view(groupFieldIds = listOf("F_ASSIGNEES"))))
                val board = base.copy(fields = base.fields + assignees)

                val grouping = GithubBoardGrouper.group(board, board.views.single(), board.items)

                grouping.field shouldBe assignees
                grouping.supported shouldBe false
                grouping.groups.single().items.map { it.itemId } shouldContainExactly listOf("a", "b")
            }

            it("Project에서 지워진 그룹 field는 그룹 기준이 없는 것으로 본다") {
                val board = board(listOf(item("a")), listOf(view(groupFieldIds = listOf("F_GONE"))))

                val grouping = GithubBoardGrouper.group(board, board.views.single(), board.items)

                grouping.field shouldBe null
                grouping.supported shouldBe true
                grouping.groups.single().items.map { it.itemId } shouldContainExactly listOf("a")
            }
        }
    })
