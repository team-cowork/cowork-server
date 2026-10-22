package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.GithubSyncCommand
import com.cowork.project.domain.github.event.GithubSyncCommandPublisher
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.cowork.project.domain.github.repository.GithubSyncRequestRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.global.projection.ProjectionSnapshotLock
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant

class GithubSyncRequestSchedulerTest :
    DescribeSpec({

        val now = Instant.parse("2026-10-22T12:00:00Z")
        lateinit var projectGithubRepoRepository: ProjectGithubRepoRepository
        lateinit var installationRepository: TeamGithubInstallationRepository
        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var requestRepository: GithubSyncRequestRepository
        lateinit var publisher: GithubSyncCommandPublisher
        lateinit var scheduler: GithubSyncRequestScheduler

        beforeEach {
            projectGithubRepoRepository = mockk()
            installationRepository = mockk()
            markerRepository = mockk()
            requestRepository = mockk(relaxed = true)
            publisher = mockk(relaxed = true)
            val snapshotLock = mockk<ProjectionSnapshotLock>()
            every { snapshotLock.tryRun(any(), any()) } answers {
                secondArg<() -> Unit>().invoke()
                true
            }
            scheduler = GithubSyncRequestScheduler(
                projectGithubRepoRepository = projectGithubRepoRepository,
                installationRepository = installationRepository,
                markerRepository = markerRepository,
                requestRepository = requestRepository,
                publisher = publisher,
                snapshotLock = snapshotLock,
                requestCooldownMs = Duration.ofMinutes(30).toMillis(),
                staleAfterMs = Duration.ofDays(7).toMillis(),
            )
        }

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
