package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.ChatGithubIssueCreateOperation
import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.ChatGithubIssueCreateCommand
import com.cowork.project.domain.github.event.ChatGithubIssueCreateResult
import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubIssueCreateCommand
import com.cowork.project.domain.github.repository.ChatGithubIssueCreateOperationRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

class ChatGithubIssueCommandProcessorTest :
    DescribeSpec({

        lateinit var operationRepository: ChatGithubIssueCreateOperationRepository
        lateinit var projectAccessGuard: ProjectAccessGuard
        lateinit var projectGithubRepoRepository: ProjectGithubRepoRepository
        lateinit var commandPublisher: GithubActionCommandPublisher
        lateinit var outboxWriter: OutboxWriter
        lateinit var processor: ChatGithubIssueCommandProcessor

        val project = Project(id = 1L, teamId = 100L, name = "p", description = null, createdBy = 1L)

        fun repoLink(url: String = "https://github.com/my-org/my-repo") = ProjectGithubRepo(
            id = 5L,
            projectId = 1L,
            teamId = 100L,
            githubRepoUrl = url,
        )

        fun command(
            projectId: Long = 1L,
            channelId: Long = 3L,
            teamId: Long = 100L,
            requesterId: Long = 42L,
            operationId: String = "11111111-1111-1111-1111-111111111111",
        ) = ChatGithubIssueCreateCommand(
            operationId = operationId,
            idempotencyKey = "22222222-2222-2222-2222-222222222222",
            projectId = projectId,
            channelId = channelId,
            teamId = teamId,
            requesterId = requesterId,
            title = "배포 오류",
            body = "재현 절차",
            occurredAt = Instant.parse("2026-09-26T00:00:00Z"),
        )

        beforeEach {
            operationRepository = mockk()
            projectAccessGuard = mockk()
            projectGithubRepoRepository = mockk()
            commandPublisher = mockk()
            outboxWriter = mockk()
            processor = ChatGithubIssueCommandProcessor(
                operationRepository,
                projectAccessGuard,
                projectGithubRepoRepository,
                commandPublisher,
                outboxWriter,
            )

            every { operationRepository.existsById(any()) } returns false
            every { operationRepository.save(any()) } answers { firstArg() }
            every { commandPublisher.publishIssueCreate(any()) } just Runs
            every { outboxWriter.enqueue(any(), any(), any()) } just Runs
        }

        describe("ChatGithubIssueCommandProcessor 클래스의 process 메서드는") {

            context("프로젝트 EDITOR/OWNER이면") {
                it("ACCEPTED로 처리하고 github.issue.create를 발행한다") {
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns project
                    every { projectGithubRepoRepository.findAllByProjectId(1L) } returns listOf(repoLink())
                    every { projectAccessGuard.requireProjectModifier(project, 42L) } just Runs

                    processor.process(command())

                    val publishedCommand = slot<GithubIssueCreateCommand>()
                    verify { commandPublisher.publishIssueCreate(capture(publishedCommand)) }
                    publishedCommand.captured.owner shouldBe "my-org"
                    publishedCommand.captured.repo shouldBe "my-repo"
                    publishedCommand.captured.channelId shouldBe 3L
                    publishedCommand.captured.teamId shouldBe 100L
                    publishedCommand.captured.requesterId shouldBe 42L

                    val operation = slot<ChatGithubIssueCreateOperation>()
                    verify { operationRepository.save(capture(operation)) }
                    operation.captured.status.name shouldBe "ACCEPTED"

                    val result = slot<ChatGithubIssueCreateResult>()
                    verify { outboxWriter.enqueue("project.chat-github-issue.result", "3", capture(result)) }
                    result.captured.status shouldBe "ACCEPTED"
                    result.captured.error shouldBe null
                }
            }

            context("프로젝트 멤버가 아니어도 팀 OWNER/ADMIN이면") {
                it("ACCEPTED로 처리하고 github.issue.create를 발행한다") {
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns project
                    every { projectGithubRepoRepository.findAllByProjectId(1L) } returns listOf(repoLink())
                    every { projectAccessGuard.requireProjectModifier(project, 42L) } just Runs

                    processor.process(command())

                    verify(exactly = 1) { commandPublisher.publishIssueCreate(any()) }
                }
            }

            context("프로젝트 VIEWER이거나 프로젝트 수정 권한이 없으면") {
                it("REJECTED로 처리하고 발행하지 않는다") {
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns project
                    every { projectGithubRepoRepository.findAllByProjectId(1L) } returns listOf(repoLink())
                    every { projectAccessGuard.requireProjectModifier(project, 42L) } throws
                        ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)

                    processor.process(command())

                    verify(exactly = 0) { commandPublisher.publishIssueCreate(any()) }
                    val operation = slot<ChatGithubIssueCreateOperation>()
                    verify { operationRepository.save(capture(operation)) }
                    operation.captured.status.name shouldBe "REJECTED"
                    operation.captured.errorMessage shouldBe "프로젝트 수정 권한이 없습니다."

                    val result = slot<ChatGithubIssueCreateResult>()
                    verify { outboxWriter.enqueue(any(), any(), capture(result)) }
                    result.captured.status shouldBe "REJECTED"
                    result.captured.error?.message shouldBe "프로젝트 수정 권한이 없습니다."
                }
            }

            context("프로젝트가 채널의 팀에 속하지 않으면") {
                it("REJECTED로 처리하고 발행하지 않는다") {
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns project

                    processor.process(command(teamId = 999L))

                    verify(exactly = 0) { commandPublisher.publishIssueCreate(any()) }
                    verify(exactly = 0) { projectGithubRepoRepository.findAllByProjectId(any()) }
                    val result = slot<ChatGithubIssueCreateResult>()
                    verify { outboxWriter.enqueue(any(), any(), capture(result)) }
                    result.captured.status shouldBe "REJECTED"
                    result.captured.error?.code shouldBe "TEAM_SCOPE_MISMATCH"
                }
            }

            context("연결된 GitHub 저장소가 없으면") {
                it("REJECTED로 처리하고 발행하지 않는다") {
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns project
                    every { projectGithubRepoRepository.findAllByProjectId(1L) } returns emptyList()

                    processor.process(command())

                    verify(exactly = 0) { commandPublisher.publishIssueCreate(any()) }
                    val result = slot<ChatGithubIssueCreateResult>()
                    verify { outboxWriter.enqueue(any(), any(), capture(result)) }
                    result.captured.error?.code shouldBe "GITHUB_REPO_NOT_FOUND"
                }
            }

            context("연결된 GitHub 저장소가 여러 개면") {
                it("REJECTED로 처리하고 발행하지 않는다") {
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns project
                    every { projectGithubRepoRepository.findAllByProjectId(1L) } returns
                        listOf(repoLink(), repoLink("https://github.com/my-org/other-repo"))

                    processor.process(command())

                    verify(exactly = 0) { commandPublisher.publishIssueCreate(any()) }
                    val result = slot<ChatGithubIssueCreateResult>()
                    verify { outboxWriter.enqueue(any(), any(), capture(result)) }
                    result.captured.error?.code shouldBe "GITHUB_REPO_AMBIGUOUS"
                }
            }

            context("이미 처리된 operationId가 재전달되면") {
                it("재처리도 중복 발행도 하지 않는다") {
                    every { operationRepository.existsById("11111111-1111-1111-1111-111111111111") } returns true

                    processor.process(command())

                    verify(exactly = 0) { projectAccessGuard.findProjectOrThrow(any()) }
                    verify(exactly = 0) { commandPublisher.publishIssueCreate(any()) }
                    verify(exactly = 0) { operationRepository.save(any()) }
                    verify(exactly = 0) { outboxWriter.enqueue(any(), any(), any()) }
                }
            }
        }
    })
