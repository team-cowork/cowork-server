package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.Optional

class GithubSyncMarkersTest :
    DescribeSpec({

        lateinit var repository: GithubSyncMarkerRepository
        lateinit var markers: GithubSyncMarkers

        beforeEach {
            repository = mockk(relaxed = true)
            markers = GithubSyncMarkers(repository, ObjectMapper().findAndRegisterModules())
        }

        fun marker(repo: String?, completedAt: String): String {
            val repoJson = repo?.let { "\"$it\"" } ?: "null"
            return """{"recordType":"SYNC_COMPLETED","owner":"my-org","repo":$repoJson,"completedAt":"$completedAt"}"""
        }

        describe("GithubSyncMarkers 클래스의 recordIfMarker 메서드는") {
            context("엔티티 state 레코드인 경우") {
                it("기록하지 않고 false를 반환한다") {
                    val state = """{"owner":"my-org","repo":"my-repo","number":1}"""

                    val recorded = markers.recordIfMarker(GithubSyncStream.ISSUE, state)

                    recorded shouldBe false
                    verify(exactly = 0) { repository.save(any()) }
                }
            }

            context("처음 받은 repo 마커인 경우") {
                it("토픽과 repo 기준으로 마커를 저장한다") {
                    every { repository.findById("ISSUE:my-org/my-repo") } returns Optional.empty()
                    val saved = slot<GithubSyncMarker>()
                    every { repository.save(capture(saved)) } answers { saved.captured }

                    val recorded =
                        markers.recordIfMarker(GithubSyncStream.ISSUE, marker("my-repo", "2026-01-02T00:00:00Z"))

                    recorded shouldBe true
                    saved.captured.id shouldBe "ISSUE:my-org/my-repo"
                    saved.captured.completedAt shouldBe Instant.parse("2026-01-02T00:00:00Z")
                }
            }

            context("owner/repo의 대소문자가 GitHub 정식 이름과 다른 경우") {
                it("소문자로 맞춘 같은 마커 id로 기록하고 조회한다") {
                    every { repository.findById("ISSUE:my-org/my-repo") } returns Optional.empty()
                    every { repository.existsById("ISSUE:my-org/my-repo") } returns true
                    every { repository.save(any<GithubSyncMarker>()) } answers { firstArg() }
                    val markerJson = """{"recordType":"SYNC_COMPLETED","owner":"My-Org","repo":"My-Repo",""" +
                        """"completedAt":"2026-01-02T00:00:00Z"}"""

                    markers.recordIfMarker(GithubSyncStream.ISSUE, markerJson)
                    markers.requireSynced(GithubSyncStream.ISSUE, "MY-ORG", "my-REPO")

                    verify { repository.save(match { it.id == "ISSUE:my-org/my-repo" }) }
                }
            }

            context("이미 기록된 마커보다 오래된 마커인 경우") {
                it("완료 시각을 되돌리지 않는다") {
                    val existing = GithubSyncMarker("REPO:my-org", Instant.parse("2026-01-02T00:00:00Z"))
                    every { repository.findById("REPO:my-org") } returns Optional.of(existing)

                    markers.recordIfMarker(GithubSyncStream.REPO, marker(null, "2026-01-01T00:00:00Z"))

                    existing.completedAt shouldBe Instant.parse("2026-01-02T00:00:00Z")
                }
            }
        }
    })
