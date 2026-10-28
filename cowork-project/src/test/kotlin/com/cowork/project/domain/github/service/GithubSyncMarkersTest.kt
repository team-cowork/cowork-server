package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GithubSyncCompletedMarker
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class GithubSyncMarkersTest :
    DescribeSpec({

        lateinit var repository: GithubSyncMarkerRepository
        lateinit var repoProjectionRepository: GithubRepoProjectionRepository
        lateinit var markers: GithubSyncMarkers

        beforeEach {
            repository = mockk(relaxed = true)
            repoProjectionRepository = mockk()
            markers = GithubSyncMarkers(repository, repoProjectionRepository)
        }

        fun marker(repo: String?, completedAt: String, owner: String = "my-org") =
            GithubSyncCompletedMarker("SYNC_COMPLETED", owner, repo, Instant.parse(completedAt))

        describe("GithubSyncMarkers 클래스의 isMarker 메서드는") {
            context("엔티티 state 레코드인 경우") {
                it("false를 반환한다") {
                    val state = jacksonObjectMapper().readTree("""{"owner":"my-org","repo":"my-repo","number":1}""")

                    markers.isMarker(state) shouldBe false
                }
            }
        }

        describe("GithubSyncMarkers 클래스의 record 메서드는") {

            context("처음 받은 repo 마커인 경우") {
                it("토픽과 repo 기준으로 마커를 저장한다") {
                    every { repository.findById("ISSUE:my-org/my-repo") } returns Optional.empty()
                    val saved = slot<GithubSyncMarker>()
                    every { repository.save(capture(saved)) } answers { saved.captured }

                    markers.record(GithubSyncStream.ISSUE, marker("my-repo", "2026-01-02T00:00:00Z"))

                    saved.captured.id shouldBe "ISSUE:my-org/my-repo"
                    saved.captured.completedAt shouldBe Instant.parse("2026-01-02T00:00:00Z")
                }
            }

            context("owner/repo의 대소문자가 GitHub 정식 이름과 다른 경우") {
                it("소문자로 맞춘 같은 마커 id로 기록하고 조회한다") {
                    every { repository.findById("ISSUE:my-org/my-repo") } returns Optional.empty()
                    every { repository.existsById("ISSUE:my-org/my-repo") } returns true
                    every { repository.save(any<GithubSyncMarker>()) } answers { firstArg() }
                    markers.record(GithubSyncStream.ISSUE, marker("My-Repo", "2026-01-02T00:00:00Z", owner = "My-Org"))
                    markers.requireSynced(GithubSyncStream.ISSUE, "MY-ORG", "my-REPO")

                    verify { repository.save(match { it.id == "ISSUE:my-org/my-repo" }) }
                }
            }

            context("이미 기록된 마커보다 오래된 마커인 경우") {
                it("완료 시각을 되돌리지 않는다") {
                    val existing = GithubSyncMarker("REPO:my-org", Instant.parse("2026-01-02T00:00:00Z"))
                    every { repository.findById("REPO:my-org") } returns Optional.of(existing)

                    markers.record(GithubSyncStream.REPO, marker(null, "2026-01-01T00:00:00Z"))

                    existing.completedAt shouldBe Instant.parse("2026-01-02T00:00:00Z")
                }
            }
        }

        describe("GithubSyncMarkers 클래스의 requireSynced 메서드는") {
            fun repoProjection(deleted: Boolean) = GithubRepoProjection(
                id = "my-org/my-repo",
                installationId = 1L,
                owner = "my-org",
                repo = "my-repo",
                fullName = "My-Org/My-Repo",
                private = false,
                htmlUrl = "https://github.com/My-Org/My-Repo",
                deleted = deleted,
                sourceOccurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

            context("마커가 없고 repo가 설치 범위에서 빠지거나 삭제된 경우") {
                it("동기화 지연과 구분해 404를 던진다") {
                    every { repository.existsById("ISSUE:my-org/my-repo") } returns false
                    every { repoProjectionRepository.findById("my-org/my-repo") } returns
                        Optional.of(repoProjection(deleted = true))

                    val ex = shouldThrow<ExpectedException> {
                        markers.requireSynced(GithubSyncStream.ISSUE, "My-Org", "My-Repo")
                    }

                    ex.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }

            context("마커가 없고 repo가 삭제되지 않은 경우") {
                it("503을 던진다") {
                    every { repository.existsById("ISSUE:my-org/my-repo") } returns false
                    every { repoProjectionRepository.findById("my-org/my-repo") } returns
                        Optional.of(repoProjection(deleted = false))

                    val ex = shouldThrow<ExpectedException> {
                        markers.requireSynced(GithubSyncStream.ISSUE, "my-org", "my-repo")
                    }

                    ex.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                }
            }
        }

        describe("GithubSyncMarkers 클래스의 invalidate 메서드는") {
            context("repo까지 알 수 있는 경우") {
                it("그 repo의 마커만 지운다") {
                    markers.invalidate(GithubSyncStream.ISSUE, "My-Org", "My-Repo")

                    verify { repository.deleteById("ISSUE:my-org/my-repo") }
                    verify(exactly = 0) { repository.deleteByIdStartingWith(any()) }
                }
            }

            context("owner만 알 수 있는 경우") {
                it("그 owner에 속한 repo 마커를 모두 지운다") {
                    markers.invalidate(GithubSyncStream.LABEL, "my-org", null)

                    verify { repository.deleteByIdStartingWith("LABEL:my-org/") }
                }
            }

            context("repo 목록 토픽인 경우") {
                it("org 단위 마커를 지운다") {
                    markers.invalidate(GithubSyncStream.REPO, "my-org", "my-repo")

                    verify { repository.deleteById("REPO:my-org") }
                }
            }

            context("대상을 알 수 없는 경우") {
                it("그 토픽의 마커를 모두 지운다") {
                    markers.invalidate(GithubSyncStream.PULL_REQUEST, null, null)

                    verify { repository.deleteByIdStartingWith("PULL_REQUEST:") }
                }
            }
        }
    })
