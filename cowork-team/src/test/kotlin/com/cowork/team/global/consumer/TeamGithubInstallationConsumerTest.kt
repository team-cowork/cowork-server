package com.cowork.team.global.consumer

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.TeamGithubInstallationRevision
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamGithubInstallationRevisionRepository
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.support.TeamGithubStateSupport
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class TeamGithubInstallationConsumerTest :
    DescribeSpec({

        lateinit var teamRepository: TeamRepository
        lateinit var teamGithubStateSupport: TeamGithubStateSupport
        lateinit var teamEventPublisher: TeamEventPublisher
        lateinit var revisionRepository: TeamGithubInstallationRevisionRepository
        lateinit var consumer: TeamGithubInstallationConsumer

        beforeEach {
            teamRepository = mockk()
            teamGithubStateSupport = mockk()
            teamEventPublisher = mockk(relaxed = true)
            revisionRepository = mockk()
            consumer = TeamGithubInstallationConsumer(
                teamRepository,
                teamGithubStateSupport,
                teamEventPublisher,
                revisionRepository,
            )
        }

        fun team(id: Long = 100L) = Team(id = id, name = "team", description = null, iconUrl = null, ownerId = 1L)

        // ledger를 처음 만나는 installation처럼 스텁한다: insertInitialIfAbsent로 최초 행을 만들고
        // (이미 있어도 no-op), 잠긴 조회로 그 행을 그대로 돌려받는다.
        fun stubNoLedger(installationId: Long) {
            val ledger = TeamGithubInstallationRevision.initial(installationId)
            every { revisionRepository.insertInitialIfAbsent(installationId) } returns 1
            every { revisionRepository.findByInstallationIdForUpdate(installationId) } returns ledger
            every { revisionRepository.save(ledger) } returns ledger
        }

        // 이미 revision이 기록된 installation처럼 스텁한다.
        fun stubLedger(installationId: Long, revision: Long): TeamGithubInstallationRevision {
            val ledger = TeamGithubInstallationRevision(installationId = installationId, revision = revision)
            every { revisionRepository.insertInitialIfAbsent(installationId) } returns 0
            every { revisionRepository.findByInstallationIdForUpdate(installationId) } returns ledger
            every { revisionRepository.save(ledger) } returns ledger
            return ledger
        }

        describe("TeamGithubInstallationConsumer 클래스의") {

            describe("revision 원장 처리는") {
                context("같은 installation의 connected(revision=1) 다음 disconnected(revision=2)가 순서대로 도착하면") {
                    it("두 이벤트를 모두 반영하고 원장을 각각 갱신한다") {
                        val existing = team()
                        val ledger = TeamGithubInstallationRevision(installationId = 1L, revision = 0)
                        every { revisionRepository.insertInitialIfAbsent(1L) } returns 0
                        every { revisionRepository.findByInstallationIdForUpdate(1L) } returns ledger
                        every { revisionRepository.save(ledger) } returns ledger
                        every { teamGithubStateSupport.verifyState("valid-state") } returns (100L to 10L)
                        every { teamRepository.findByGithubInstallationId(1L) } returns null andThen existing
                        every { teamRepository.findByIdForUpdate(100L) } returns existing
                        every { teamRepository.save(existing) } returns existing

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "valid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 1L,
                            ),
                        )
                        ledger.revision shouldBe 1L
                        existing.githubInstallationId shouldBe 1L

                        consumer.consumeDisconnected(TeamGithubDisconnectedPayload(installationId = 1L, revision = 2L))
                        ledger.revision shouldBe 2L
                        existing.githubInstallationId shouldBe null

                        verify(exactly = 2) { teamRepository.save(existing) }
                        verify(exactly = 2) { teamEventPublisher.publishUpdated(existing, any(), any()) }
                    }
                }

                context("disconnected(revision=2)가 먼저 처리된 뒤 늦게 connected(revision=1)가 도착하면") {
                    it("낮은 revision의 connected를 무시하고 Team을 변경하지 않는다") {
                        val ledger = stubLedger(installationId = 1L, revision = 2L)

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "valid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 1L,
                            ),
                        )

                        ledger.revision shouldBe 2L
                        verify(exactly = 0) { teamGithubStateSupport.verifyState(any()) }
                        verify(exactly = 0) { teamRepository.findByIdForUpdate(any()) }
                        verify(exactly = 0) { teamRepository.save(any()) }
                        verify(exactly = 0) { teamEventPublisher.publishUpdated(any(), any(), any()) }
                    }
                }

                context("이미 반영된 것과 같은 revision이 재전송되면") {
                    it("connected 이벤트를 무시한다") {
                        val ledger = stubLedger(installationId = 1L, revision = 1L)

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "valid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 1L,
                            ),
                        )

                        ledger.revision shouldBe 1L
                        verify(exactly = 0) { teamGithubStateSupport.verifyState(any()) }
                        verify(exactly = 0) { teamRepository.save(any()) }
                    }

                    it("disconnected 이벤트를 무시한다") {
                        val ledger = stubLedger(installationId = 1L, revision = 2L)

                        consumer.consumeDisconnected(TeamGithubDisconnectedPayload(installationId = 1L, revision = 2L))

                        ledger.revision shouldBe 2L
                        verify(exactly = 0) { teamRepository.findByGithubInstallationId(any()) }
                        verify(exactly = 0) { teamRepository.save(any()) }
                    }
                }

                context("revision 필드가 없던 구버전 이벤트(기본값 0)가 도착하면") {
                    it("원장을 조회·생성하지 않고 그대로 처리한다") {
                        val existing = team()
                        every { teamGithubStateSupport.verifyState("valid-state") } returns (100L to 10L)
                        every { teamRepository.findByGithubInstallationId(1L) } returns null
                        every { teamRepository.findByIdForUpdate(100L) } returns existing
                        every { teamRepository.save(existing) } returns existing

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "valid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 0L,
                            ),
                        )

                        existing.githubInstallationId shouldBe 1L
                        verify(exactly = 0) { revisionRepository.insertInitialIfAbsent(any()) }
                        verify(exactly = 0) { revisionRepository.findByInstallationIdForUpdate(any()) }
                        verify(exactly = 0) { revisionRepository.save(any()) }
                    }
                }
            }

            describe("consumeConnected 메서드는") {
                context("state가 유효하고 revision이 최신이면") {
                    it("팀에 installation을 연결한다") {
                        val existing = team()
                        stubNoLedger(1L)
                        every { teamGithubStateSupport.verifyState("valid-state") } returns (100L to 10L)
                        every { teamRepository.findByGithubInstallationId(1L) } returns null
                        every { teamRepository.findByIdForUpdate(100L) } returns existing
                        every { teamRepository.save(existing) } returns existing

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "valid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 1L,
                            ),
                        )

                        existing.githubInstallationId shouldBe 1L
                        existing.githubOrgLogin shouldBe "my-org"
                        verify(exactly = 1) { teamRepository.save(existing) }
                        verify(exactly = 1) { teamEventPublisher.publishUpdated(existing, 10L, any()) }
                    }
                }

                context("state 검증에 실패하면") {
                    it("이벤트를 무시한다") {
                        stubNoLedger(1L)
                        every { teamGithubStateSupport.verifyState("invalid-state") } throws
                            ExpectedException("유효하지 않은 state입니다.", HttpStatus.BAD_REQUEST)

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "invalid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 1L,
                            ),
                        )

                        verify(exactly = 0) { teamRepository.findByIdForUpdate(any()) }
                        verify(exactly = 0) { teamRepository.save(any()) }
                    }
                }

                context("installation이 이미 다른 팀에 연결돼 있으면") {
                    it("연결 요청을 무시한다") {
                        val ownedByOtherTeam = team(id = 200L)
                        stubNoLedger(1L)
                        every { teamGithubStateSupport.verifyState("valid-state") } returns (100L to 10L)
                        every { teamRepository.findByGithubInstallationId(1L) } returns ownedByOtherTeam

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "valid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 1L,
                            ),
                        )

                        verify(exactly = 0) { teamRepository.findByIdForUpdate(any()) }
                        verify(exactly = 0) { teamRepository.save(any()) }
                    }
                }

                context("팀이 이미 다른 installation에 연결돼 있으면") {
                    it("명시적 해제 없이 조용히 교체하지 않는다") {
                        val existing = team()
                        existing.connectGithub(installationId = 2L, orgLogin = "other-org")
                        stubNoLedger(1L)
                        every { teamGithubStateSupport.verifyState("valid-state") } returns (100L to 10L)
                        every { teamRepository.findByGithubInstallationId(1L) } returns null
                        every { teamRepository.findByIdForUpdate(100L) } returns existing

                        consumer.consumeConnected(
                            TeamGithubConnectedPayload(
                                "valid-state",
                                installationId = 1L,
                                orgLogin = "my-org",
                                revision = 1L,
                            ),
                        )

                        existing.githubInstallationId shouldBe 2L
                        verify(exactly = 0) { teamRepository.save(any()) }
                    }
                }
            }

            describe("consumeDisconnected 메서드는") {
                context("installationId가 현재 연결과 일치하면") {
                    it("연결을 해제한다") {
                        val existing = team()
                        existing.connectGithub(installationId = 1L, orgLogin = "my-org")
                        stubNoLedger(1L)
                        every { teamRepository.findByGithubInstallationId(1L) } returns existing
                        every { teamRepository.findByIdForUpdate(100L) } returns existing
                        every { teamRepository.save(existing) } returns existing

                        consumer.consumeDisconnected(TeamGithubDisconnectedPayload(installationId = 1L, revision = 1L))

                        existing.githubInstallationId shouldBe null
                        verify(exactly = 1) { teamRepository.save(existing) }
                        verify(exactly = 1) { teamEventPublisher.publishUpdated(existing, existing.ownerId, any()) }
                    }
                }

                context("현재 연결된 installationId와 다르면") {
                    it("해제하지 않는다") {
                        val existing = team()
                        existing.connectGithub(installationId = 2L, orgLogin = "my-org")
                        stubNoLedger(1L)
                        every { teamRepository.findByGithubInstallationId(1L) } returns existing
                        every { teamRepository.findByIdForUpdate(100L) } returns existing

                        consumer.consumeDisconnected(TeamGithubDisconnectedPayload(installationId = 1L, revision = 1L))

                        existing.githubInstallationId shouldBe 2L
                        verify(exactly = 0) { teamRepository.save(any()) }
                    }
                }
            }
        }
    })
