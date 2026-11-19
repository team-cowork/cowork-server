package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.event.GithubAddItemPayload
import com.cowork.project.domain.githubBoard.event.GithubClearFieldPayload
import com.cowork.project.domain.githubBoard.event.GithubConvertDraftPayload
import com.cowork.project.domain.githubBoard.event.GithubDraftPayload
import com.cowork.project.domain.githubBoard.event.GithubProjectItemTarget
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandPublisher
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import com.cowork.project.domain.githubBoard.event.GithubSetAssigneesPayload
import com.cowork.project.domain.githubBoard.event.GithubSetFieldPayload
import com.cowork.project.domain.githubBoard.presentation.data.request.AddGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.ConvertGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.CreateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardAssigneesReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardFieldReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.UpdateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.service.impl.WriteGithubBoardItemServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class WriteGithubBoardItemServiceImplTest :
    DescribeSpec({
        val link = ProjectGithubBoard(7L, 3L, 1L, "my-org", 2, createdBy = 10L)
        lateinit var resolver: GithubBoardAccessResolver
        lateinit var reader: GithubBoardReader
        lateinit var repos: GithubBoardRepositoryResolver
        lateinit var publisher: GithubProjectItemWriteCommandPublisher
        lateinit var service: WriteGithubBoardItemServiceImpl
        val payload = slot<Any>()
        val target = slot<GithubProjectItemTarget>()
        val type = slot<GithubProjectItemWriteCommandType>()
        beforeEach {
            resolver = mockk()
            reader = mockk()
            repos = mockk()
            publisher = mockk()
            service = WriteGithubBoardItemServiceImpl(resolver, reader, repos, publisher)
            every { resolver.resolve(20L, 3L, 7L, GithubBoardAccess.WRITE) } returns link
            every { reader.read(link) } returns board(
                listOf(
                    item("issue").copy(contentNodeId = "I_1"),
                    item("draft", type = "DRAFT_ISSUE").copy(contentNodeId = "DI_1"),
                    item("pr", type = "PULL_REQUEST").copy(contentNodeId = "PR_1"),
                ),
            )
            every { repos.resolve(link, 5L) } returns GithubRepoRef("my-org", "app")
            every { repos.resolveContent(link, "my-org/app") } returns GithubRepoRef("my-org", "app")
            every { publisher.publish(capture(target), capture(type), capture(payload), 20L, any()) } returns "op-1"
        }

        describe("WriteGithubBoardItemServiceImpl 클래스의 쓰기 메서드는") {
            context("보드 수정 권한과 유효한 입력이 있으면") {
                it("필드 값만 바꾸는 명령을 만든다") {
                    val result = service.setField(
                        20L,
                        3L,
                        7L,
                        "issue",
                        "F_POINTS",
                        SetGithubBoardFieldReqDto(number = 3.5),
                    )
                    result.operationId shouldBe "op-1"
                    type.captured shouldBe GithubProjectItemWriteCommandType.SET_FIELD
                    payload.captured shouldBe GithubSetFieldPayload("F_POINTS", mapOf("number" to 3.5))
                    target.captured shouldBe GithubProjectItemTarget(7L, 3L, "my-org", "PVT_1", "issue")
                }
                it("필드 비우기 명령을 만든다") {
                    service.clearField(20L, 3L, 7L, "issue", "F_POINTS")
                    type.captured shouldBe GithubProjectItemWriteCommandType.CLEAR_FIELD
                    payload.captured shouldBe GithubClearFieldPayload("F_POINTS")
                }
                it("카드 ID 없이 draft 생성 명령을 만든다") {
                    service.createDraft(20L, 3L, 7L, CreateGithubBoardDraftReqDto("계획", "본문"))
                    type.captured shouldBe GithubProjectItemWriteCommandType.CREATE_DRAFT
                    payload.captured shouldBe GithubDraftPayload("계획", "본문")
                    target.captured.itemId shouldBe null
                }
                it("제목을 유지하면서 본문을 비우는 명령을 만든다") {
                    service.updateDraft(20L, 3L, 7L, "draft", UpdateGithubBoardDraftReqDto(body = ""))
                    type.captured shouldBe GithubProjectItemWriteCommandType.UPDATE_DRAFT
                    payload.captured shouldBe GithubDraftPayload(null, "")
                }
                it("연결된 저장소의 이슈 또는 PR 번호로 카드 추가 명령을 만든다") {
                    service.addItem(20L, 3L, 7L, AddGithubBoardItemReqDto(5L, 42))
                    type.captured shouldBe GithubProjectItemWriteCommandType.ADD_ITEM
                    payload.captured shouldBe GithubAddItemPayload("app", 42)
                    target.captured.itemId shouldBe null
                }
                listOf("draft", "issue", "pr").forEach { itemId ->
                    it("$itemId 카드를 보드에서 제거하는 명령을 만든다") {
                        service.removeItem(20L, 3L, 7L, itemId)
                        type.captured shouldBe GithubProjectItemWriteCommandType.REMOVE_ITEM
                        target.captured.itemId shouldBe itemId
                        payload.captured shouldBe emptyMap<String, String>()
                    }
                }
                it("선택한 저장소로 draft 전환 명령을 만든다") {
                    service.convertDraft(20L, 3L, 7L, "draft", ConvertGithubBoardDraftReqDto(5L))
                    type.captured shouldBe GithubProjectItemWriteCommandType.CONVERT_DRAFT
                    payload.captured shouldBe GithubConvertDraftPayload("app")
                }
                listOf(listOf("alice", "bob"), emptyList()).forEach { names ->
                    it("담당자를 $names 목록으로 교체하는 명령을 만든다") {
                        service.setAssignees(20L, 3L, 7L, "issue", SetGithubBoardAssigneesReqDto(names))
                        type.captured shouldBe GithubProjectItemWriteCommandType.SET_ASSIGNEES
                        payload.captured shouldBe GithubSetAssigneesPayload("app", names)
                    }
                }
            }
            context("요청이 허용되지 않으면") {
                val calls = listOf<Pair<String, () -> Unit>>(
                    "필드 변경" to
                        { service.setField(20L, 3L, 7L, "issue", "F_POINTS", SetGithubBoardFieldReqDto(number = 1.0)) },
                    "필드 비우기" to { service.clearField(20L, 3L, 7L, "issue", "F_POINTS") },
                    "생성" to { service.createDraft(20L, 3L, 7L, CreateGithubBoardDraftReqDto("제목")) },
                    "수정" to { service.updateDraft(20L, 3L, 7L, "draft", UpdateGithubBoardDraftReqDto(title = "제목")) },
                    "추가" to { service.addItem(20L, 3L, 7L, AddGithubBoardItemReqDto(5L, 1)) },
                    "제거" to { service.removeItem(20L, 3L, 7L, "issue") },
                    "전환" to { service.convertDraft(20L, 3L, 7L, "draft", ConvertGithubBoardDraftReqDto(5L)) },
                    "담당자 변경" to
                        { service.setAssignees(20L, 3L, 7L, "issue", SetGithubBoardAssigneesReqDto(emptyList())) },
                )
                calls.forEach { (name, call) ->
                    it("수정 권한이 없는 $name 요청은 명령을 발행하지 않는다") {
                        every { resolver.resolve(20L, 3L, 7L, GithubBoardAccess.WRITE) } throws
                            ExpectedException("GitHub 보드 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
                        shouldThrow<ExpectedException> { call() }.statusCode shouldBe HttpStatus.FORBIDDEN
                        verify(exactly = 0) { publisher.publish(any(), any(), any(), any(), any()) }
                    }
                }
                it("없는 카드의 필드를 변경하지 않는다") {
                    shouldThrow<ExpectedException> { service.clearField(20L, 3L, 7L, "missing", "F_POINTS") }
                        .statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { publisher.publish(any(), any(), any(), any(), any()) }
                }
                it("이슈를 draft 수정 API로 편집하지 않는다") {
                    shouldThrow<ExpectedException> {
                        service.updateDraft(20L, 3L, 7L, "issue", UpdateGithubBoardDraftReqDto(title = "제목"))
                    }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
                it("변경할 내용이 없는 draft 요청을 거부한다") {
                    shouldThrow<ExpectedException> {
                        service.updateDraft(20L, 3L, 7L, "draft", UpdateGithubBoardDraftReqDto())
                    }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                }
                listOf(" ", "https://github.com/other-org/private/issues/1").forEach { title ->
                    it("잘못된 draft 제목 '$title'을 거부한다") {
                        shouldThrow<ExpectedException> {
                            service.createDraft(20L, 3L, 7L, CreateGithubBoardDraftReqDto(title))
                        }
                            .statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
                listOf("draft", "pr").forEach { itemId ->
                    it("$itemId 카드의 담당자 변경을 거부한다") {
                        shouldThrow<ExpectedException> {
                            service.setAssignees(20L, 3L, 7L, itemId, SetGithubBoardAssigneesReqDto(listOf("alice")))
                        }.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
                it("대소문자만 다른 중복 담당자를 거부한다") {
                    shouldThrow<ExpectedException> {
                        service.setAssignees(
                            20L,
                            3L,
                            7L,
                            "issue",
                            SetGithubBoardAssigneesReqDto(listOf("alice", "ALICE")),
                        )
                    }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
                listOf(listOf(null), listOf(" "), listOf("ali ce"), List(11) { "user$it" }).forEach { names ->
                    it("유효하지 않은 담당자 목록 $names 요청을 거부한다") {
                        shouldThrow<ExpectedException> {
                            service.setAssignees(20L, 3L, 7L, "issue", SetGithubBoardAssigneesReqDto(names))
                        }.statusCode shouldBe HttpStatus.BAD_REQUEST
                        verify(exactly = 0) { publisher.publish(any(), any(), any(), any(), any()) }
                    }
                }
                it("저장소 소유 검사가 실패하면 카드 추가 명령을 발행하지 않는다") {
                    every { repos.resolve(link, 5L) } throws ExpectedException("저장소 권한이 없습니다.", HttpStatus.FORBIDDEN)
                    shouldThrow<ExpectedException> { service.addItem(20L, 3L, 7L, AddGithubBoardItemReqDto(5L, 1)) }
                        .statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { publisher.publish(any(), any(), any(), any(), any()) }
                }
            }
        }
    })
