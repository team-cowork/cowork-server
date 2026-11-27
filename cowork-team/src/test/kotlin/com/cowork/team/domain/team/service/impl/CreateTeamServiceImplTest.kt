package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.TeamMemberships
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamRole
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.global.policy.AccountPermissionGuard
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime

class CreateTeamServiceImplTest :
    DescribeSpec({
        lateinit var savedTeam: Team
        lateinit var savedOwner: TeamMember
        lateinit var service: CreateTeamServiceImpl

        beforeEach {
            val teamRepository = mockk<TeamRepository>()
            val memberRepository = mockk<TeamMemberRepository>()
            val now = LocalDateTime.now()
            every { teamRepository.save(any()) } answers {
                firstArg<Team>().also {
                    it.createdAt = now
                    it.updatedAt = now
                    savedTeam = it
                }
            }
            every { memberRepository.save(any()) } answers {
                firstArg<TeamMember>().also { savedOwner = it }
            }
            service = CreateTeamServiceImpl(
                mockk<AccountPermissionGuard>(relaxed = true),
                teamRepository,
                TeamMemberships(memberRepository, mockk(relaxed = true)),
                mockk<TeamEventPublisher>(relaxed = true),
            )
        }

        describe("CreateTeamServiceImpl 클래스의 execute 메서드는") {
            context("사용자가 팀을 만들면") {
                it("요청 내용을 가진 팀과 OWNER 멤버를 생성한다") {
                    val result = service.execute(
                        7L,
                        CreateTeamRequest("cowork", "core team"),
                    )

                    result.name shouldBe "cowork"
                    result.description shouldBe "core team"
                    result.iconUrl shouldBe null
                    result.ownerId shouldBe 7L
                    savedTeam.ownerId shouldBe 7L
                    savedOwner.teamId shouldBe savedTeam.id
                    savedOwner.userId shouldBe 7L
                    savedOwner.role shouldBe TeamRole.OWNER
                }
            }
        }
    })
