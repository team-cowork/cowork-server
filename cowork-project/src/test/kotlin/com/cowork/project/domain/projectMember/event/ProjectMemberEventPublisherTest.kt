package com.cowork.project.domain.projectMember.event

import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.repository.ProjectMemberEventTombstoneRepository
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

class ProjectMemberEventPublisherTest :
    DescribeSpec({
        lateinit var entityManager: EntityManager
        lateinit var outboxWriter: OutboxWriter
        lateinit var tombstoneRepository: ProjectMemberEventTombstoneRepository
        lateinit var publisher: ProjectMemberEventPublisher

        beforeEach {
            entityManager = mockk(relaxed = true)
            outboxWriter = mockk()
            tombstoneRepository = mockk(relaxed = true)
            publisher = ProjectMemberEventPublisher(entityManager, outboxWriter, tombstoneRepository)
        }

        describe("publishAdded 메서드는") {
            it("재가입 시 tombstone보다 1 microsecond 큰 버전을 저장하고 tombstone을 제거한다") {
                val eventSlot = slot<ProjectMemberEvent>()
                val tombstoneVersion = Instant.parse("2026-08-27T00:00:00.123456Z")
                val tombstone = ProjectMemberEventTombstone(
                    id = 3L,
                    projectId = 7L,
                    userId = 11L,
                    stateOccurredAt = tombstoneVersion,
                )
                val member = ProjectMember(
                    projectId = 7L,
                    userId = 11L,
                    stateOccurredAt = tombstoneVersion.minusSeconds(10),
                )
                every { tombstoneRepository.findByProjectIdAndUserIdForUpdate(7L, 11L) } returns tombstone
                every { tombstoneRepository.delete(tombstone) } just runs
                every { outboxWriter.enqueue("project.member.event", "7:11", capture(eventSlot)) } just runs

                publisher.publishAdded(member, tombstoneVersion.minusSeconds(1))

                val expectedVersion = Instant.parse("2026-08-27T00:00:00.123457Z")
                member.stateOccurredAt shouldBe expectedVersion
                eventSlot.captured.occurredAt shouldBe expectedVersion
                eventSlot.captured.eventType shouldBe ProjectMemberEventType.ADDED
                verify(exactly = 1) { tombstoneRepository.delete(tombstone) }
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("project.member.event", "7:11", any())
                }
            }
        }

        describe("publishRemoved 메서드는") {
            it("활성 관계보다 큰 버전의 durable tombstone과 제거 이벤트를 만든다") {
                val eventSlot = slot<ProjectMemberEvent>()
                val tombstoneSlot = slot<ProjectMemberEventTombstone>()
                val currentVersion = Instant.parse("2026-08-27T00:00:00.123456Z")
                val member = ProjectMember(projectId = 8L, userId = 11L, stateOccurredAt = currentVersion)
                every { tombstoneRepository.findByProjectIdAndUserIdForUpdate(8L, 11L) } returns null
                every { tombstoneRepository.save(capture(tombstoneSlot)) } answers { firstArg() }
                every { outboxWriter.enqueue("project.member.event", "8:11", capture(eventSlot)) } just runs

                publisher.publishRemoved(member, currentVersion.minusSeconds(1))

                val expectedVersion = Instant.parse("2026-08-27T00:00:00.123457Z")
                member.stateOccurredAt shouldBe expectedVersion
                tombstoneSlot.captured.stateOccurredAt shouldBe expectedVersion
                eventSlot.captured.occurredAt shouldBe expectedVersion
                eventSlot.captured.eventType shouldBe ProjectMemberEventType.REMOVED
            }
        }

        describe("publishSnapshot 메서드는") {
            it("발행 시각 대신 관계에 저장된 상태 버전을 그대로 재사용한다") {
                val eventSlot = slot<ProjectMemberEvent>()
                val stateVersion = Instant.parse("2026-08-26T12:00:00.123456Z")
                every { outboxWriter.enqueue("project.member.event", "9:11", capture(eventSlot)) } just runs
                val member = ProjectMember(projectId = 9L, userId = 11L, stateOccurredAt = stateVersion)

                publisher.publishSnapshot(member)

                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe stateVersion
            }
        }
    })
