package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncRequest
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.event.GITHUB_SYNC_COMMAND_TOPIC
import com.cowork.project.domain.github.event.GithubSyncCommand
import com.cowork.project.domain.github.event.GithubSyncCommandPublisher
import com.cowork.project.domain.github.event.GithubSyncCompletedMarker
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.cowork.project.domain.github.repository.GithubSyncRequestRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.ProjectionSnapshotLock
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.Optional

class GithubSyncRequestSchedulerProjectTest :
    DescribeSpec({
        lateinit var boardRepository: ProjectGithubBoardRepository
        lateinit var installationRepository: TeamGithubInstallationRepository
        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var requestRepository: GithubSyncRequestRepository
        lateinit var publisher: GithubSyncCommandPublisher
        lateinit var outboxWriter: OutboxWriter
        lateinit var scheduler: GithubSyncRequestScheduler

        beforeEach {
            val repoRepository = mockk<ProjectGithubRepoRepository>()
            every { repoRepository.findAll() } returns emptyList()
            boardRepository = mockk()
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
            every { requestRepository.findAllById(any()) } returns emptyList()
            scheduler = GithubSyncRequestScheduler(
                projectGithubRepoRepository = repoRepository,
                projectGithubBoardRepository = boardRepository,
                repoProjectionRepository = mockk<GithubRepoProjectionRepository>(),
                installationRepository = installationRepository,
                markerRepository = markerRepository,
                requestRepository = requestRepository,
                publisher = publisher,
                outboxWriter = outboxWriter,
                snapshotLock = snapshotLock,
                requestCooldownMs = Duration.ofMinutes(30).toMillis(),
                staleAfterMs = Duration.ofDays(7).toMillis(),
                projectStaleAfterMs = Duration.ofHours(1).toMillis(),
                manualCooldownMs = Duration.ofMinutes(1).toMillis(),
            )
        }

        fun board(teamId: Long, owner: String, number: Int) = ProjectGithubBoard(
            projectId = teamId,
            teamId = teamId,
            ownerLogin = owner,
            projectNumber = number,
            createdBy = 1L,
        )

        describe("requestSyncs 메서드는") {
            it("팀 조직이 소유한 Project만 PROJECT 대상으로 요청하고, 마커가 1시간보다 오래되면 다시 요청한다") {
                every { installationRepository.findAll() } returns
                    listOf(TeamGithubInstallation(teamId = 1L, installationId = 10L, orgLogin = "My-Org"))
                every { boardRepository.findAll() } returns listOf(board(1L, "my-org", 3), board(1L, "other-org", 4))
                val fresh = Instant.now()
                val stale = Instant.now().minus(Duration.ofHours(2))
                every { markerRepository.findAllById(any()) } returns listOf(
                    GithubSyncMarker(GithubSyncMarker.id(GithubSyncStream.REPO, "my-org", null), fresh),
                    GithubSyncMarker(GithubSyncMarker.id(GithubSyncStream.PROJECT, "my-org", "#3"), stale),
                    GithubSyncMarker(GithubSyncMarker.id(GithubSyncStream.PROJECT_ITEM, "my-org", "#3"), fresh),
                )
                val commands = mutableListOf<GithubSyncCommand>()
                every { publisher.publish(capture(commands)) } returns Unit

                scheduler.requestSyncs()

                commands.map { it.target to it.projectNumber } shouldContainExactlyInAnyOrder listOf("PROJECT" to 3)
                commands.single().owner shouldBe "my-org"
            }
        }

        describe("requestProjectSync 메서드는") {
            it("새로 연결한 Project는 outbox로 바로 요청한다") {
                every { requestRepository.findById("PROJECT:my-org#3") } returns Optional.empty()
                every { markerRepository.findAllById(any()) } returns emptyList()

                scheduler.requestProjectSync("My-Org", 3, force = false) shouldBe true

                verify { outboxWriter.enqueue(GITHUB_SYNC_COMMAND_TOPIC, "my-org#3", any()) }
                verify { requestRepository.upsert("PROJECT:my-org#3", any()) }
            }

            it("수동 요청은 마커가 최신이어도 요청하지만 짧은 쿨다운 안에서는 다시 요청하지 않는다") {
                every { requestRepository.findById("PROJECT:my-org#3") } returns
                    Optional.of(GithubSyncRequest("PROJECT:my-org#3", Instant.now().minusSeconds(120)))
                scheduler.requestProjectSync("my-org", 3, force = true) shouldBe true

                every { requestRepository.findById("PROJECT:my-org#3") } returns
                    Optional.of(GithubSyncRequest("PROJECT:my-org#3", Instant.now().minusSeconds(10)))
                scheduler.requestProjectSync("my-org", 3, force = true) shouldBe false
            }
        }

        describe("GithubSyncCompletedMarker는") {
            it("Project 번호를 마커 대상으로 쓴다") {
                GithubSyncCompletedMarker("SYNC_COMPLETED", "my-org", null, Instant.EPOCH, 3).scope shouldBe "#3"
                GithubSyncCompletedMarker("SYNC_COMPLETED", "my-org", "app", Instant.EPOCH).scope shouldBe "app"
            }
        }
    })
