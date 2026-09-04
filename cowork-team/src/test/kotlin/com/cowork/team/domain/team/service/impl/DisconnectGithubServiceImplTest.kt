package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class DisconnectGithubServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var service: DisconnectGithubServiceImpl

        beforeEach {
            teamRepository = mockk(relaxed = true)
            memberRepository = mockk()
            service = DisconnectGithubServiceImpl(
                teamRepository,
                TeamAccessGuard(teamRepository, memberRepository),
                mockk<TeamEventPublisher>(relaxed = true),
            )
        }

        describe("DisconnectGithubServiceImpl 클래스의 execute 메서드는") {
            context("OWNER가 연결된 GitHub 조직을 해제하면") {
                it("팀에서 설치 정보와 조직 정보를 제거한다") {
                    val team = team().also { it.connectGithub(42L, "my-org") }
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(
                            1L,
                            10L,
                            listOf(TeamRole.OWNER, TeamRole.ADMIN),
                        )
                    } returns TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
                    every { teamRepository.save(team) } returns team

                    service.execute(10L, 1L)

                    team.githubInstallationId.shouldBeNull()
                    team.githubOrgLogin.shouldBeNull()
                    verify(exactly = 1) { teamRepository.save(team) }
                }
            }

            context("GitHub 연결 정보가 없으면") {
                it("팀을 변경하지 않는다") {
                    val team = team()
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(
                            1L,
                            10L,
                            listOf(TeamRole.OWNER, TeamRole.ADMIN),
                        )
                    } returns TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)

                    service.execute(10L, 1L)

                    verify(exactly = 0) { teamRepository.save(any()) }
                }
            }

            context("일반 멤버가 연결 해제를 요청하면") {
                it("FORBIDDEN으로 거부한다") {
                    every { teamRepository.findByIdForUpdate(1L) } returns team()
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(
                            1L,
                            42L,
                            listOf(TeamRole.OWNER, TeamRole.ADMIN),
                        )
                    } returns null

                    val error = shouldThrow<ExpectedException> { service.execute(42L, 1L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { teamRepository.save(any()) }
                }
            }
        }
    }) {
    companion object {
        private fun team() = Team(
            id = 1L,
            name = "team",
            description = null,
            iconUrl = null,
            ownerId = 10L,
        )
    }
}
