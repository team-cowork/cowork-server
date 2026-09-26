package com.cowork.project.domain.github.event

import com.cowork.project.domain.github.entity.GithubIssueWriteOperation
import com.cowork.project.domain.github.entity.GithubIssueWriteOperationStatus
import com.cowork.project.domain.github.repository.GithubIssueWriteOperationRepository
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant

class GithubIssueWriteCommandPublisherTest :
    DescribeSpec({

        lateinit var operationRepository: GithubIssueWriteOperationRepository
        lateinit var outboxWriter: OutboxWriter
        lateinit var publisher: GithubIssueWriteCommandPublisher

        val savedOperations = mutableListOf<GithubIssueWriteOperation>()
        val existingIds = mutableSetOf<String>()

        beforeEach {
            savedOperations.clear()
            existingIds.clear()
            operationRepository = mockk()
            outboxWriter = mockk()
            publisher = GithubIssueWriteCommandPublisher(operationRepository, outboxWriter)

            every { operationRepository.existsById(any()) } answers { existingIds.contains(firstArg()) }
            every { operationRepository.save(any()) } answers {
                val operation = firstArg<GithubIssueWriteOperation>()
                existingIds.add(operation.operationId)
                savedOperations.add(operation)
                operation
            }
            every { outboxWriter.enqueue(any(), any(), any()) } just Runs
        }

        describe("GithubIssueWriteCommandPublisher 클래스의") {
            describe("publishReplaceLabels 메서드는") {
                it("\"owner/repo\"를 Kafka 키로 사용해 REPLACE_LABELS 커맨드를 발행하고 PENDING operation을 남긴다") {
                    publisher.publishReplaceLabels(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        issueNumber = 3,
                        labels = listOf("bug"),
                        requestedBy = 7L,
                        occurredAt = Instant.parse("2026-09-26T00:00:00Z"),
                    )

                    val commandSlot = slot<GithubIssueWriteCommand>()
                    verify(exactly = 1) {
                        outboxWriter.enqueue(GITHUB_ISSUE_WRITE_COMMAND_TOPIC, "my-org/my-repo", capture(commandSlot))
                    }
                    commandSlot.captured.commandType shouldBe GithubIssueWriteCommandType.REPLACE_LABELS
                    commandSlot.captured.owner shouldBe "my-org"
                    commandSlot.captured.repo shouldBe "my-repo"
                    commandSlot.captured.requestedBy shouldBe 7L
                    (commandSlot.captured.payload as GithubReplaceLabelsPayload).labels shouldBe listOf("bug")

                    savedOperations shouldHaveSize 1
                    savedOperations.single().status shouldBe GithubIssueWriteOperationStatus.PENDING
                    savedOperations.single().repoId shouldBe 5L
                    savedOperations.single().issueNumber shouldBe 3
                    savedOperations.single().commandType shouldBe GithubIssueWriteCommandType.REPLACE_LABELS
                }
            }

            describe("publishCreateComment 메서드는") {
                it("같은 이슈에 대해 여러 번 호출해도 매번 새 operation을 발행한다(내용이 다른 별개 댓글일 수 있으므로 nonce로 구분)") {
                    publisher.publishCreateComment(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        issueNumber = 3,
                        body = "첫 댓글",
                        requesterGithubUsername = "octocat",
                        parentType = GithubCommentParentType.ISSUE,
                        requestedBy = 7L,
                        occurredAt = Instant.now(),
                    )
                    publisher.publishCreateComment(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        issueNumber = 3,
                        body = "두 번째 댓글",
                        requesterGithubUsername = "octocat",
                        parentType = GithubCommentParentType.ISSUE,
                        requestedBy = 7L,
                        occurredAt = Instant.now(),
                    )

                    verify(exactly = 2) { outboxWriter.enqueue(GITHUB_ISSUE_WRITE_COMMAND_TOPIC, "my-org/my-repo", any()) }
                    savedOperations.map { it.operationId }.toSet() shouldHaveSize 2
                }

                it("나중에 부모 작성자 알림을 보낼 수 있도록 owner/repo/parentType을 operation에 남긴다") {
                    publisher.publishCreateComment(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        issueNumber = 3,
                        body = "확인했습니다",
                        requesterGithubUsername = "octocat",
                        parentType = GithubCommentParentType.PULL_REQUEST,
                        requestedBy = 7L,
                        occurredAt = Instant.now(),
                    )

                    savedOperations.single().owner shouldBe "my-org"
                    savedOperations.single().repo shouldBe "my-repo"
                    savedOperations.single().parentType shouldBe GithubCommentParentType.PULL_REQUEST
                }
            }

            describe("publishUpdateComment 메서드는") {
                it("같은 댓글을 다시 수정해도 매번 새 operation을 발행한다") {
                    publisher.publishUpdateComment(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        commentId = 42L,
                        body = "처음 수정",
                        requestedBy = 7L,
                        occurredAt = Instant.now(),
                    )
                    publisher.publishUpdateComment(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        commentId = 42L,
                        body = "다시 수정",
                        requestedBy = 7L,
                        occurredAt = Instant.now(),
                    )

                    verify(exactly = 2) { outboxWriter.enqueue(GITHUB_ISSUE_WRITE_COMMAND_TOPIC, "my-org/my-repo", any()) }
                    savedOperations.map { it.operationId }.toSet() shouldHaveSize 2
                }
            }

            describe("publishDeleteComment 메서드는") {
                it("같은 댓글에 대한 두 번째 삭제 요청은 재발행하지 않고 기존 operationId를 재사용한다") {
                    val first = publisher.publishDeleteComment(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        commentId = 42L,
                        requestedBy = 7L,
                        occurredAt = Instant.now(),
                    )
                    val second = publisher.publishDeleteComment(
                        owner = "my-org",
                        repo = "my-repo",
                        repoId = 5L,
                        commentId = 42L,
                        requestedBy = 7L,
                        occurredAt = Instant.now(),
                    )

                    first shouldBe second
                    verify(exactly = 1) { outboxWriter.enqueue(any(), any(), any()) }
                    savedOperations shouldHaveSize 1
                }
            }
        }
    })
