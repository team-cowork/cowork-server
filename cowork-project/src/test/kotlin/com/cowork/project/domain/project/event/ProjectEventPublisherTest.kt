package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ProjectEventPublisherTest :
    DescribeSpec({
        val entityManager = mockk<EntityManager>(relaxed = true)
        val outboxWriter = mockk<OutboxWriter>()
        val publisher = ProjectEventPublisher(entityManager, outboxWriter)

        describe("publishUpdated 메서드는") {
            it("projectId를 key로 사용하고 projection 필드를 모두 발행한다") {
                val eventSlot = slot<ProjectEvent>()
                every { outboxWriter.enqueue("project.event", "7", capture(eventSlot)) } just runs
                val project = Project(
                    id = 7L,
                    teamId = 3L,
                    name = "Backend",
                    description = "API",
                    position = 4,
                    createdBy = 11L,
                )

                publisher.publishUpdated(project)

                eventSlot.captured.projectId shouldBe 7L
                eventSlot.captured.teamId shouldBe 3L
                eventSlot.captured.position shouldBe 4
                verify(exactly = 1) { outboxWriter.enqueue("project.event", "7", any()) }
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("project.event", "7", any())
                }
            }
        }

        describe("publishSnapshot 메서드는") {
            it("발행 시각이 아니라 project source updatedAt을 UTC version으로 사용한다") {
                val eventSlot = slot<ProjectEvent>()
                every { outboxWriter.enqueue("project.event", "8", capture(eventSlot)) } just runs
                val updatedAt = LocalDateTime.of(2026, 8, 26, 12, 0, 0, 123_456_789)
                val project = Project(
                    id = 8L,
                    teamId = 3L,
                    name = "snapshot",
                    description = null,
                    createdBy = 11L,
                    updatedAt = updatedAt,
                )

                publisher.publishSnapshot(project)

                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe
                    updatedAt.atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.MICROS)
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("project.event", "8", any())
                }
            }
        }
    })
