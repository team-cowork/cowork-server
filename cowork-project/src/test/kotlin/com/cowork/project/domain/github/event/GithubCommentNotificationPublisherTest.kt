package com.cowork.project.domain.github.event

import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class GithubCommentNotificationPublisherTest :
    DescribeSpec({

        lateinit var outboxWriter: OutboxWriter
        lateinit var publisher: GithubCommentNotificationPublisher

        beforeEach {
            outboxWriter = mockk()
            publisher = GithubCommentNotificationPublisher(outboxWriter)

            every { outboxWriter.enqueue(any(), any(), any(), any()) } returns Unit
        }

        describe("GithubCommentNotificationPublisher 클래스의") {
            describe("publishCommentCreated 메서드는") {
                context("알림 대상과 데이터가 주어지면") {
                    it("Kafka로 직접 보내지 않고 outbox에 알림 이벤트를 기록한다") {
                        val data = mapOf(
                            "repo" to "my-org/my-repo",
                            "number" to 3,
                            "parentType" to "ISSUE",
                            "commentAuthor" to "commenter",
                            "body" to "확인했습니다",
                            "htmlUrl" to "https://github.com/x",
                        )
                        val payloadSlot = slot<NotificationTriggerEvent>()
                        every {
                            outboxWriter.enqueue("notification.trigger", "42", capture(payloadSlot))
                        } returns Unit

                        publisher.publishCommentCreated(targetUserId = 42L, data = data)

                        verify(exactly = 1) {
                            outboxWriter.enqueue("notification.trigger", "42", any())
                        }
                        payloadSlot.captured.type shouldBe "GITHUB_COMMENT_CREATED"
                        payloadSlot.captured.targetUserIds shouldBe listOf(42L)
                        payloadSlot.captured.data shouldBe data
                    }
                }
            }
        }
    })
