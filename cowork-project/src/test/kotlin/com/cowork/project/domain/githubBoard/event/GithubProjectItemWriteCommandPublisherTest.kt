package com.cowork.project.domain.githubBoard.event

import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperation
import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperationStatus
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemWriteOperationRepository
import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant

class GithubProjectItemWriteCommandPublisherTest :
    DescribeSpec({
        describe("GithubProjectItemWriteCommandPublisher는") {
            it("PENDING 작업을 저장하고 Project node ID를 키로 커맨드를 outbox에 넣는다") {
                val repository = mockk<GithubProjectItemWriteOperationRepository>()
                val outbox = mockk<OutboxWriter>(relaxed = true)
                val saved = slot<GithubProjectItemWriteOperation>()
                every { repository.save(capture(saved)) } answers { saved.captured }
                val command = slot<Any>()
                val publisher = GithubProjectItemWriteCommandPublisher(repository, outbox)
                val target = GithubProjectItemTarget(7L, 3L, "my-org", "PVT_1", "PVTI_1")
                val payload = GithubReorderItemPayload("PVTI_0")

                val first = publisher.publish(
                    target,
                    GithubProjectItemWriteCommandType.REORDER_ITEM,
                    payload,
                    20L,
                    Instant.EPOCH,
                )
                verify { outbox.enqueue(GITHUB_PROJECT_ITEM_WRITE_COMMAND_TOPIC, "PVT_1", capture(command)) }
                val second = publisher.publish(
                    target,
                    GithubProjectItemWriteCommandType.REORDER_ITEM,
                    payload,
                    20L,
                    Instant.EPOCH,
                )

                saved.captured.status shouldBe GithubProjectItemWriteOperationStatus.PENDING
                saved.captured.boardId shouldBe 7L
                (command.captured as GithubProjectItemWriteCommand).let {
                    it.operationId shouldBe first
                    it.owner shouldBe "my-org"
                    it.itemId shouldBe "PVTI_1"
                    it.payload shouldBe payload
                }
                second shouldNotBe first
            }
        }
    })
