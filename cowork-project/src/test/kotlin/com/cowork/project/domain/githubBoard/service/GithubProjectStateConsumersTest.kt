package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import com.cowork.project.domain.githubBoard.entity.GithubProjectItemProjection
import com.cowork.project.domain.githubBoard.entity.GithubProjectProjection
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectStateEvent
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemProjectionRepository
import com.cowork.project.domain.githubBoard.repository.GithubProjectProjectionRepository
import com.cowork.project.global.consumer.GithubProjectItemStateConsumer
import com.cowork.project.global.consumer.GithubProjectStateConsumer
import com.cowork.project.global.consumer.GithubStateRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class GithubProjectStateConsumersTest :
    DescribeSpec({
        val mapper = jacksonObjectMapper()
        val record = ConsumerRecord("topic", 0, 0L, "key", "{}")
        lateinit var processor: GithubStateRecordProcessor

        fun <T : Any> deliver(event: T) {
            every {
                processor.process(any(), any(), any(), any<Class<T>>(), any(), any(), any())
            } answers { lastArg<(T) -> Unit>().invoke(event) }
        }

        beforeEach { processor = mockk() }

        describe("GithubProjectItemStateConsumer는") {
            lateinit var repository: GithubProjectItemProjectionRepository
            lateinit var consumer: GithubProjectItemStateConsumer
            val saved = slot<GithubProjectItemProjection>()

            beforeEach {
                repository = mockk()
                every { repository.save(capture(saved)) } answers { saved.captured }
                consumer = GithubProjectItemStateConsumer(repository, processor, ProjectionStreams(), mapper)
            }

            fun existing(version: String, deleted: Boolean = false) = GithubProjectItemProjection(
                id = "a",
                projectNodeId = "PVT_1",
                owner = "my-org",
                payloadJson = "{}",
                deleted = deleted,
                sourceOccurredAt = Instant.parse(version),
            )

            it("새 item을 대상 없이도 저장한다") {
                every { repository.findById("a") } returns Optional.empty()
                deliver(
                    item("a", statusOption = "opt-done").copy(sourceOccurredAt = Instant.parse("2026-11-01T00:00:00Z")),
                )

                consumer.consume(record)

                saved.captured.projectNodeId shouldBe "PVT_1"
                mapper.readValue(saved.captured.payloadJson, GithubProjectItemStateEvent::class.java)
                    .fieldValues.single().optionId shouldBe "opt-done"
                verify {
                    processor.process(
                        any(),
                        GithubSyncStream.PROJECT_ITEM,
                        record,
                        any<Class<*>>(),
                        any(),
                        false,
                        any(),
                    )
                }
            }

            it("웹훅으로 바뀐 컬럼 값을 반영하고 더 오래된 레코드는 무시한다") {
                every { repository.findById("a") } returns Optional.of(existing("2026-11-02T00:00:00Z"))

                deliver(item("a").copy(sourceOccurredAt = Instant.parse("2026-11-01T00:00:00Z")))
                consumer.consume(record)
                verify(exactly = 0) { repository.save(any()) }

                deliver(
                    item("a", statusOption = "opt-todo").copy(sourceOccurredAt = Instant.parse("2026-11-03T00:00:00Z")),
                )
                consumer.consume(record)
                saved.captured.sourceOccurredAt shouldBe Instant.parse("2026-11-03T00:00:00Z")
            }

            it("같은 버전의 레코드로 삭제를 되살리지 않는다") {
                every { repository.findById("a") } returns Optional.of(existing("2026-11-02T00:00:00Z", deleted = true))
                deliver(item("a").copy(sourceOccurredAt = Instant.parse("2026-11-02T00:00:00Z")))

                consumer.consume(record)

                verify(exactly = 0) { repository.save(any()) }
            }

            it("보관과 삭제 상태를 반영한다") {
                every { repository.findById("a") } returns Optional.of(existing("2026-11-02T00:00:00Z"))
                deliver(item("a").copy(archived = true, sourceOccurredAt = Instant.parse("2026-11-04T00:00:00Z")))

                consumer.consume(record)

                saved.captured.archived shouldBe true
            }
        }

        describe("GithubProjectStateConsumer는") {
            it("field, view, item 순서를 JSON으로 저장하고 접근 오류를 남긴다") {
                val repository = mockk<GithubProjectProjectionRepository>()
                val saved = slot<GithubProjectProjection>()
                every { repository.findById("my-org/3") } returns Optional.empty()
                every { repository.save(capture(saved)) } answers { saved.captured }
                val consumer = GithubProjectStateConsumer(repository, processor, ProjectionStreams(), mapper)
                deliver(
                    GithubProjectStateEvent(
                        owner = "My-Org",
                        projectNumber = 3,
                        nodeId = "PVT_1",
                        itemIds = listOf("b", "a"),
                        accessError = null,
                        sourceOccurredAt = Instant.parse("2026-11-01T00:00:00Z"),
                    ),
                )

                consumer.consume(record)

                saved.captured.owner shouldBe "my-org"
                saved.captured.itemIdsJson shouldBe """["b","a"]"""
                saved.captured.nodeId shouldBe "PVT_1"
            }
        }
    })
