package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.GITHUB_SYNC_COMMAND_TOPIC
import com.cowork.project.domain.github.event.GithubSyncCommand
import com.cowork.project.domain.github.event.GithubSyncCommandPublisher
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.cowork.project.domain.github.repository.GithubSyncRequestRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.ProjectionSnapshotLock
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.Optional

class GithubSyncRequestSchedulerTest :
    DescribeSpec({

        val now = Instant.parse("2026-10-22T12:00:00Z")
        lateinit var projectGithubRepoRepository: ProjectGithubRepoRepository
        lateinit var repoProjectionRepository: GithubRepoProjectionRepository
        lateinit var installationRepository: TeamGithubInstallationRepository
        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var requestRepository: GithubSyncRequestRepository
        lateinit var publisher: GithubSyncCommandPublisher
        lateinit var outboxWriter: OutboxWriter
        lateinit var scheduler: GithubSyncRequestScheduler

        beforeEach {
            projectGithubRepoRepository = mockk()
            repoProjectionRepository = mockk()
            every { repoProjectionRepository.findAllById(any()) } returns emptyList()
            installationRepository = mockk()
            markerRepository = mockk()
            requestRepository = mockk(relaxed = true)
            publisher = mockk(relaxed = true)
            outboxWriter = mockk(relaxed = true)
            val snapshotLock = mockk<ProjectionSnapshotLock>()
            every { snapshotLock.tryRun(any(), any()) } answers {
                secondArg<() -> Unit>().invoke()
                true
            }
            scheduler = GithubSyncRequestScheduler(
                projectGithubRepoRepository = projectGithubRepoRepository,
                repoProjectionRepository = repoProjectionRepository,
                installationRepository = installationRepository,
                markerRepository = markerRepository,
                requestRepository = requestRepository,
                publisher = publisher,
                outboxWriter = outboxWriter,
                snapshotLock = snapshotLock,
                requestCooldownMs = Duration.ofMinutes(30).toMillis(),
                staleAfterMs = Duration.ofDays(7).toMillis(),
            )
        }

        fun repoProjection(repo: String, deleted: Boolean) = GithubRepoProjection(
            id = GithubRepoProjection.id("my-org", repo),
            installationId = 1L,
            owner = "my-org",
            repo = repo,
            fullName = "my-org/$repo",
            private = false,
            htmlUrl = "https://github.com/my-org/$repo",
            deleted = deleted,
            sourceOccurredAt = now,
        )

        describe("GithubSyncRequestScheduler 클래스의") {
            describe("requestSyncs 메서드는") {
                context("연결 URL의 대소문자만 다른 같은 repo가 둘인 경우") {
                    it("소문자로 맞춘 하나의 대상으로 한 번만 요청한다") {
                        every { installationRepository.findAll() } returns emptyList()
                        every { projectGithubRepoRepository.findAll() } returns listOf(
                            ProjectGithubRepo(
                                projectId = 1L,
                                teamId = 1L,
                                githubRepoUrl = "https://github.com/My-Org/My-Repo",
                            ),
                            ProjectGithubRepo(
                                projectId = 2L,
                                teamId = 2L,
                                githubRepoUrl = "https://github.com/my-org/my-repo",
                            ),
                        )
                        every { markerRepository.findAllById(any()) } returns emptyList()
                        every { requestRepository.findAllById(any()) } returns emptyList()
                        val command = slot<GithubSyncCommand>()
                        every { publisher.publish(capture(command)) } returns Unit

                        scheduler.requestSyncs()

                        verify(exactly = 1) { publisher.publish(any()) }
                        command.captured.owner shouldBe "my-org"
                        command.captured.repo shouldBe "my-repo"
                    }
                }

                context("연결된 repo가 설치 범위에서 빠지거나 삭제된 경우") {
                    it("그 repo는 요청하지 않고 나머지 repo만 요청한다") {
                        every { installationRepository.findAll() } returns emptyList()
                        every { projectGithubRepoRepository.findAll() } returns listOf(
                            ProjectGithubRepo(
                                projectId = 1L,
                                teamId = 1L,
                                githubRepoUrl = "https://github.com/my-org/removed",
                            ),
                            ProjectGithubRepo(
                                projectId = 2L,
                                teamId = 1L,
                                githubRepoUrl = "https://github.com/my-org/active",
                            ),
                        )
                        every { repoProjectionRepository.findAllById(any()) } returns listOf(
                            repoProjection("removed", deleted = true),
                            repoProjection("active", deleted = false),
                        )
                        every { markerRepository.findAllById(any()) } returns emptyList()
                        every { requestRepository.findAllById(any()) } returns emptyList()
                        val command = slot<GithubSyncCommand>()
                        every { publisher.publish(capture(command)) } returns Unit

                        scheduler.requestSyncs()

                        verify(exactly = 1) { publisher.publish(any()) }
                        command.captured.repo shouldBe "active"
                    }
                }
            }

            describe("requestRepositorySync 메서드는") {
                context("완료 마커가 없고 요청한 적도 없는 repo인 경우") {
                    it("소문자로 맞춘 대상을 outbox로 요청하고 요청 시각을 남긴다") {
                        every { markerRepository.findAllById(any()) } returns emptyList()
                        every { requestRepository.findById("REPOSITORY:my-org/my-repo") } returns Optional.empty()
                        val command = slot<Any>()

                        scheduler.requestRepositorySync(GithubRepoRef("My-Org", "My-Repo"))

                        verify(exactly = 1) {
                            outboxWriter.enqueue(GITHUB_SYNC_COMMAND_TOPIC, "my-org/my-repo", capture(command))
                        }
                        (command.captured as GithubSyncCommand).target shouldBe "REPOSITORY"
                        verify(exactly = 1) { requestRepository.upsert("REPOSITORY:my-org/my-repo", any()) }
                        verify(exactly = 0) { publisher.publish(any()) }
                    }
                }

                context("다른 팀이 먼저 연결해 모든 완료 마커가 최신인 repo인 경우") {
                    it("요청하지 않는다") {
                        val fresh = Instant.now()
                        every { markerRepository.findAllById(any()) } answers {
                            firstArg<Iterable<String>>().map { GithubSyncMarker(it, fresh) }
                        }
                        every { requestRepository.findById(any()) } returns Optional.empty()

                        scheduler.requestRepositorySync(GithubRepoRef("my-org", "my-repo"))

                        verify(exactly = 0) { outboxWriter.enqueue(any(), any(), any(), any(), any()) }
                        verify(exactly = 0) { requestRepository.upsert(any(), any()) }
                    }
                }
            }

            describe("needsRequest 메서드는") {
                context("마커가 하나라도 없고 요청한 적도 없는 경우") {
                    it("요청한다") {
                        scheduler.needsRequest(listOf(now, null), null, now) shouldBe true
                    }
                }

                context("쿨다운 안에 이미 요청한 경우") {
                    it("마커가 없어도 다시 요청하지 않는다") {
                        scheduler.needsRequest(listOf(null), now.minus(Duration.ofMinutes(10)), now) shouldBe false
                    }
                }

                context("쿨다운이 지났는데도 마커가 없는 경우") {
                    it("다시 요청한다") {
                        scheduler.needsRequest(listOf(null), now.minus(Duration.ofMinutes(31)), now) shouldBe true
                    }
                }

                context("모든 마커가 오래되지 않은 경우") {
                    it("요청하지 않는다") {
                        scheduler.needsRequest(listOf(now.minus(Duration.ofDays(1))), null, now) shouldBe false
                    }
                }

                context("마커 하나가 오래된 경우") {
                    it("다시 동기화를 요청한다") {
                        val markers = listOf(now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(8)))

                        scheduler.needsRequest(markers, null, now) shouldBe true
                    }
                }
            }
        }
    })
