package com.cowork.project.domain.projectMember.event

import com.cowork.project.domain.projectMember.entity.ProjectMember
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

class ProjectMemberEventPublisherTest :
    DescribeSpec({
        val entityManager = mockk<EntityManager>(relaxed = true)
        val outboxWriter = mockk<OutboxWriter>()
        val publisher = ProjectMemberEventPublisher(entityManager, outboxWriter)

        describe("publishAdded 메서드는") {
            it("projectId와 userId의 복합 key로 발행한다") {
                every { outboxWriter.enqueue("project.member.event", "7:11", any()) } just runs

                publisher.publishAdded(projectId = 7L, userId = 11L)

                verify(exactly = 1) { outboxWriter.enqueue("project.member.event", "7:11", any()) }
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("project.member.event", "7:11", any())
                }
            }
        }

        describe("publishSnapshot 메서드는") {
            it("발행 시각이 아니라 membership joinedAt을 UTC version으로 사용한다") {
                val eventSlot = slot<ProjectMemberEvent>()
                every { outboxWriter.enqueue("project.member.event", "8:11", capture(eventSlot)) } just runs
                val joinedAt = LocalDateTime.of(2026, 8, 26, 12, 0, 0, 123_456_789)
                val member = ProjectMember(projectId = 8L, userId = 11L, joinedAt = joinedAt)

                publisher.publishSnapshot(member)

                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe
                    joinedAt.atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.MICROS)
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("project.member.event", "8:11", any())
                }
            }
        }
    })
