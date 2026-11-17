package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.sprint
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.view
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.event.GithubMoveItemPayload
import com.cowork.project.domain.githubBoard.event.GithubProjectItemTarget
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandPublisher
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import com.cowork.project.domain.githubBoard.event.GithubProjectViewSort
import com.cowork.project.domain.githubBoard.event.GithubReorderItemPayload
import com.cowork.project.domain.githubBoard.presentation.data.request.MoveGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.ReorderGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.service.impl.MoveGithubBoardItemServiceImpl
import com.cowork.project.domain.githubBoard.service.impl.ReorderGithubBoardItemServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubBoardItemMoveServicesTest :
    DescribeSpec({
        val link =
            ProjectGithubBoard(
                id = 7L,
                projectId = 3L,
                teamId = 1L,
                ownerLogin = "my-org",
                projectNumber = 2,
                createdBy = 10L,
            )
        lateinit var resolver: GithubBoardAccessResolver
        lateinit var reader: GithubBoardReader
        lateinit var publisher: GithubProjectItemWriteCommandPublisher
        lateinit var moveService: MoveGithubBoardItemServiceImpl
        lateinit var reorderService: ReorderGithubBoardItemServiceImpl
        val payload = slot<Any>()
        val target = slot<GithubProjectItemTarget>()

        beforeEach {
            resolver = mockk()
            reader = mockk()
            publisher = mockk()
            moveService = MoveGithubBoardItemServiceImpl(resolver, reader, publisher)
            reorderService = ReorderGithubBoardItemServiceImpl(resolver, reader, publisher)
            every { resolver.resolve(20L, 3L, 7L, GithubBoardAccess.WRITE) } returns link
            every { publisher.publish(capture(target), any(), capture(payload), 20L, any()) } returns "op-1"
            every { reader.read(link) } returns board(
                listOf(
                    item("a", statusOption = "opt-todo"),
                    item("b", statusOption = "opt-done"),
                    item("c", statusOption = "opt-todo"),
                    item("d", statusOption = "opt-done"),
                ),
            )
        }

        describe("MoveGithubBoardItemServiceImpl은") {
            it("대상 컬럼 옵션과 GitHub 전체 위치 기준 afterId로 MOVE_ITEM 커맨드를 만든다") {
                val result = moveService.execute(20L, 3L, 7L, "a", MoveGithubBoardItemReqDto(1, "opt-done", null))

                result.operationId shouldBe "op-1"
                payload.captured shouldBe GithubMoveItemPayload("F_STATUS", "opt-done", null, true, null)
                target.captured shouldBe GithubProjectItemTarget(7L, 3L, "my-org", "PVT_1", "a")
                verify { publisher.publish(any(), GithubProjectItemWriteCommandType.MOVE_ITEM, any(), 20L, any()) }
            }

            it("값이 없는 컬럼으로 옮기면 두 값을 모두 비우고, 빈 컬럼이면 위치를 바꾸지 않는다") {
                moveService.execute(20L, 3L, 7L, "b", MoveGithubBoardItemReqDto(1, null, null))

                payload.captured shouldBe GithubMoveItemPayload("F_STATUS", null, null, false, null)
            }

            it("iteration 컬럼이면 iterationId로 보낸다") {
                every { reader.read(link) } returns board(
                    listOf(item("a", iteration = "it-3"), item("b", iteration = "it-4")),
                    listOf(view(columnFieldIds = listOf(sprint.id))),
                )

                moveService.execute(20L, 3L, 7L, "a", MoveGithubBoardItemReqDto(1, "it-4", "b"))

                payload.captured shouldBe GithubMoveItemPayload("F_SPRINT", null, "it-4", true, "b")
            }

            it("정렬이 있는 뷰에서는 컬럼 값만 바꾼다") {
                every { reader.read(link) } returns board(
                    listOf(item("a", statusOption = "opt-todo"), item("b", statusOption = "opt-done")),
                    listOf(view(sortBy = listOf(GithubProjectViewSort("F_POINTS", "ASC")))),
                )

                moveService.execute(20L, 3L, 7L, "a", MoveGithubBoardItemReqDto(1, "opt-done", "b"))

                payload.captured shouldBe GithubMoveItemPayload("F_STATUS", "opt-done", null, false, null)
            }

            it("없는 컬럼이나 없는 카드는 커맨드를 만들지 않고 거부한다") {
                shouldThrow<ExpectedException> {
                    moveService.execute(20L, 3L, 7L, "a", MoveGithubBoardItemReqDto(1, "nope", null))
                }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
                shouldThrow<ExpectedException> {
                    moveService.execute(20L, 3L, 7L, "zz", MoveGithubBoardItemReqDto(1, "opt-done", null))
                }
                    .statusCode shouldBe HttpStatus.NOT_FOUND
                verify(exactly = 0) { publisher.publish(any(), any(), any(), any(), any()) }
            }

            it("보드 수정 권한이 없으면 커맨드를 만들지 않는다") {
                every { resolver.resolve(20L, 3L, 7L, GithubBoardAccess.WRITE) } throws
                    ExpectedException("GitHub 보드 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)

                shouldThrow<ExpectedException> {
                    moveService.execute(20L, 3L, 7L, "a", MoveGithubBoardItemReqDto(1, "opt-done", null))
                }
                    .statusCode shouldBe HttpStatus.FORBIDDEN
                verify(exactly = 0) { publisher.publish(any(), any(), any(), any(), any()) }
            }
        }

        describe("ReorderGithubBoardItemServiceImpl은") {
            it("컬럼 맨 위로 옮기면 컬럼 첫 카드의 앞 카드를 afterId로 쓴다") {
                reorderService.execute(20L, 3L, 7L, "d", ReorderGithubBoardItemReqDto(1, null))

                payload.captured shouldBe GithubReorderItemPayload("a")
                verify { publisher.publish(any(), GithubProjectItemWriteCommandType.REORDER_ITEM, any(), 20L, any()) }
            }

            it("기준 카드가 다른 컬럼에 있으면 거부한다") {
                shouldThrow<ExpectedException> {
                    reorderService.execute(20L, 3L, 7L, "d", ReorderGithubBoardItemReqDto(1, "a"))
                }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("정렬이 있는 뷰에서는 거부한다") {
                every { reader.read(link) } returns board(
                    listOf(item("a", statusOption = "opt-todo"), item("b", statusOption = "opt-todo")),
                    listOf(view(sortBy = listOf(GithubProjectViewSort("F_POINTS", "ASC")))),
                )

                shouldThrow<ExpectedException> {
                    reorderService.execute(20L, 3L, 7L, "b", ReorderGithubBoardItemReqDto(1, null))
                }
                    .statusCode shouldBe HttpStatus.CONFLICT
            }
        }
    })
