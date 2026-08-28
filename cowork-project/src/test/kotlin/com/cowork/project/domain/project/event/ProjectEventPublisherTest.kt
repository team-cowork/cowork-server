package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectEventTombstoneRepository
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
import java.time.Instant

class ProjectEventPublisherTest :
    DescribeSpec({
        lateinit var entityManager: EntityManager
        lateinit var outboxWriter: OutboxWriter
        lateinit var tombstoneRepository: ProjectEventTombstoneRepository
        lateinit var publisher: ProjectEventPublisher

        beforeEach {
            entityManager = mockk(relaxed = true)
            outboxWriter = mockk()
            tombstoneRepository = mockk(relaxed = true)
            publisher = ProjectEventPublisher(entityManager, outboxWriter, tombstoneRepository)
        }

        describe("publishUpdated 메서드는") {
            it("요청 시각이 현재 저장 버전보다 과거여도 정확히 1 microsecond 큰 버전을 저장하고 발행한다") {
                val eventSlot = slot<ProjectEvent>()
                every { tombstoneRepository.findByProjectIdForUpdate(7L) } returns null
                every { outboxWriter.enqueue("project.event", "7", capture(eventSlot)) } just runs
                val currentVersion = Instant.parse("2026-08-27T00:00:00.123456Z")
                val project = Project(
                    id = 7L,
                    teamId = 3L,
                    name = "Backend",
                    description = "API",
                    position = 4,
                    createdBy = 11L,
                    stateOccurredAt = currentVersion,
                )

                publisher.publishUpdated(project, currentVersion.minusSeconds(1))

                val expectedVersion = Instant.parse("2026-08-27T00:00:00.123457Z")
                project.stateOccurredAt shouldBe expectedVersion
                eventSlot.captured.occurredAt shouldBe expectedVersion
                eventSlot.captured.projectId shouldBe 7L
                eventSlot.captured.teamId shouldBe 3L
                eventSlot.captured.position shouldBe 4
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("project.event", "7", any())
                }
            }
        }

        describe("publishDeleted 메서드는") {
            it("삭제 버전과 full tombstone을 같은 발행 경로에 저장한다") {
                val eventSlot = slot<ProjectEvent>()
                val tombstoneSlot = slot<ProjectEventTombstone>()
                every { tombstoneRepository.findByProjectIdForUpdate(8L) } returns null
                every { tombstoneRepository.save(capture(tombstoneSlot)) } answers { firstArg() }
                every { outboxWriter.enqueue("project.event", "8", capture(eventSlot)) } just runs
                val currentVersion = Instant.parse("2026-08-27T00:00:00.123456Z")
                val project = Project(
                    id = 8L,
                    teamId = 3L,
                    name = "Deleted",
                    description = "durable",
                    createdBy = 11L,
                    stateOccurredAt = currentVersion,
                )

                publisher.publishDeleted(project, currentVersion.minusSeconds(1))

                val expectedVersion = Instant.parse("2026-08-27T00:00:00.123457Z")
                tombstoneSlot.captured.projectId shouldBe 8L
                tombstoneSlot.captured.name shouldBe "Deleted"
                tombstoneSlot.captured.description shouldBe "durable"
                tombstoneSlot.captured.stateOccurredAt shouldBe expectedVersion
                eventSlot.captured.eventType shouldBe ProjectEventType.DELETED
                eventSlot.captured.occurredAt shouldBe expectedVersion
            }
        }

        describe("publishSnapshot 메서드는") {
            it("발행 시각 대신 owner에 저장된 상태 버전을 그대로 재사용한다") {
                val eventSlot = slot<ProjectEvent>()
                every { outboxWriter.enqueue("project.event", "9", capture(eventSlot)) } just runs
                val stateVersion = Instant.parse("2026-08-26T12:00:00.123456Z")
                val project = Project(
                    id = 9L,
                    teamId = 3L,
                    name = "snapshot",
                    description = null,
                    createdBy = 11L,
                    stateOccurredAt = stateVersion,
                )

                publisher.publishSnapshot(project)

                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe stateVersion
                verify(exactly = 0) { tombstoneRepository.findByProjectIdForUpdate(any()) }
            }
        }
    })
