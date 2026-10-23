package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GithubIssueStateEvent
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.cowork.project.domain.github.service.GithubSyncMarkers
import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStream
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.Optional

class GithubStateRecordProcessorTest :
    DescribeSpec({

        val stream = ProjectionStream("cowork-project.github-issue-state", "github.issue.state.v1", null)

        lateinit var projectionProcessor: ProjectionRecordProcessor
        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var processor: GithubStateRecordProcessor

        beforeEach {
            projectionProcessor = mockk(relaxed = true)
            every { projectionProcessor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>()() }
            markerRepository = mockk(relaxed = true)
            processor = GithubStateRecordProcessor(
                projectionProcessor,
                GithubSyncMarkers(markerRepository),
                ObjectMapper().findAndRegisterModules(),
            )
        }

        fun record(value: String?) = ConsumerRecord("github.issue.state.v1", 0, 7L, "key", value)

        fun process(record: ConsumerRecord<String, String?>): List<GithubIssueStateEvent> {
            val applied = mutableListOf<GithubIssueStateEvent>()
            @Suppress("UNCHECKED_CAST")
            processor.process(
                stream,
                GithubSyncStream.ISSUE,
                record as ConsumerRecord<String, String>,
                GithubIssueStateEvent::class.java,
            ) { applied += it }
            return applied
        }

        describe("GithubStateRecordProcessor 클래스의 process 메서드는") {
            context("유효한 state 레코드인 경우") {
                it("체크포인트와 함께 projection에 반영한다") {
                    val record = record("""{"owner":"my-org","repo":"my-repo","number":3,"state":"open"}""")

                    val applied = process(record)

                    applied.single().number shouldBe 3
                    verify { projectionProcessor.applyRecord(stream, record as ConsumerRecord<String, String>, any()) }
                    verify(exactly = 0) { projectionProcessor.quarantineRecord(any(), any(), any()) }
                }
            }

            context("완료 마커인 경우") {
                it("projection에 반영하지 않고 마커를 기록한다") {
                    every { markerRepository.findById("ISSUE:my-org/my-repo") } returns Optional.empty()
                    every { markerRepository.save(any<GithubSyncMarker>()) } answers { firstArg() }
                    val record = record(
                        """{"recordType":"SYNC_COMPLETED","owner":"my-org","repo":"my-repo",""" +
                            """"completedAt":"2026-01-02T00:00:00Z"}""",
                    )

                    val applied = process(record)

                    applied shouldBe emptyList()
                    verify { markerRepository.save(match { it.id == "ISSUE:my-org/my-repo" }) }
                }
            }

            context("필드 타입이 맞지 않는 state 레코드인 경우") {
                it("격리하고 그 repo의 완료 마커를 지운다") {
                    val record = record("""{"owner":"my-org","repo":"my-repo","number":"not-a-number"}""")

                    val applied = process(record)

                    applied shouldBe emptyList()
                    verify { markerRepository.deleteById("ISSUE:my-org/my-repo") }
                    verify { projectionProcessor.quarantineRecord(stream, any(), any()) }
                    verify(exactly = 0) { projectionProcessor.applyRecord(any(), any(), any()) }
                }
            }

            context("repo가 없는 state 레코드인 경우") {
                it("격리하고 그 owner의 완료 마커를 모두 지운다") {
                    process(record("""{"owner":"my-org","number":3}"""))

                    verify { markerRepository.deleteByIdStartingWith("ISSUE:my-org/") }
                    verify { projectionProcessor.quarantineRecord(stream, any(), any()) }
                }
            }

            context("JSON이 아니거나 값이 없는 레코드인 경우") {
                it("격리하고 그 토픽의 완료 마커를 모두 지운다") {
                    process(record("not-json"))
                    process(record(null))

                    verify(exactly = 2) { markerRepository.deleteByIdStartingWith("ISSUE:") }
                    verify(exactly = 2) { projectionProcessor.quarantineRecord(stream, any(), any()) }
                }
            }
        }
    })
