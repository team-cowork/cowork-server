package com.cowork.project.domain.github.event

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.producer.RecordMetadata
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import team.themoment.sdk.exception.ExpectedException
import java.util.concurrent.CompletableFuture

class GithubActionCommandPublisherTest :
    DescribeSpec({

        lateinit var kafkaTemplate: KafkaTemplate<String, Any>
        lateinit var publisher: GithubActionCommandPublisher

        beforeEach {
            kafkaTemplate = mockk()
            publisher = GithubActionCommandPublisher(kafkaTemplate)
        }

        describe("GithubActionCommandPublisher 클래스의") {
            describe("publishIssueCreate 메서드는") {
                context("Kafka 발행이 성공한 경우") {
                    it("예외 없이 정상 종료한다") {
                        val sendResult = mockk<SendResult<String, Any>>()
                        every { sendResult.recordMetadata } returns mockk<RecordMetadata>(relaxed = true)
                        every { kafkaTemplate.send("github.issue.create", "my-org/my-repo", any()) } returns
                            CompletableFuture.completedFuture(sendResult)

                        publisher.publishIssueCreate(
                            GithubIssueCreateCommand("my-org", "my-repo", "제목", null, emptyList()),
                        )

                        verify { kafkaTemplate.send("github.issue.create", "my-org/my-repo", any()) }
                    }
                }

                context("Kafka 발행이 실패한 경우") {
                    it("ExpectedException(502)을 던진다") {
                        val failed = CompletableFuture<SendResult<String, Any>>()
                        failed.completeExceptionally(RuntimeException("broker down"))
                        every { kafkaTemplate.send("github.issue.create", "my-org/my-repo", any()) } returns failed

                        shouldThrow<ExpectedException> {
                            publisher.publishIssueCreate(
                                GithubIssueCreateCommand("my-org", "my-repo", "제목", null, emptyList()),
                            )
                        }
                    }
                }
            }
        }
    })
