package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamPermissionGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class RemoveTeamMemberServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var roleReader: TeamRoleProjectionReader
        lateinit var service: RemoveTeamMemberServiceImpl

        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            roleReader = mockk(relaxed = true)
            service = RemoveTeamMemberServiceImpl(
                teamRepository,
                memberRepository,
                mockk<TeamEventPublisher>(relaxed = true),
                mockk<TeamMemberEventPublisher>(relaxed = true),
                TeamPermissionGuard(memberRepository, roleReader),
            )
            every { teamRepository.findByIdForUpdate(1L) } returns team
            every { memberRepository.findByTeamIdAndUserId(any(), any()) } answers {
                memberRepository.findByTeamIdAndUserIdForUpdate(firstArg(), secondArg())
            }
        }

        fun role(priority: Int) = TeamRoleResponse(
            id = 1L,
            teamId = 1L,
            name = "강퇴 담당",
            colorHex = "#000000",
            priority = priority,
            mentionable = false,
            permissions = mapOf("kick_members" to true),
            createdAt = null,
            updatedAt = null,
        )

        describe("RemoveTeamMemberServiceImpl 클래스의 execute 메서드는") {
            context("일반 멤버가 다른 멤버를 제거하려 하면") {
                it("FORBIDDEN으로 거부한다") {
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 20L) } returns
                        TeamMember(team = team, userId = 20L)

                    val error = shouldThrow<ExpectedException> { service.execute(20L, 1L, 30L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }
            }

            context("일반 멤버가 스스로 탈퇴하면") {
                it("자신의 멤버십을 삭제한다") {
                    val member = TeamMember(team = team, userId = 20L)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 20L) } returns member
                    every { memberRepository.delete(member) } just Runs

                    service.execute(20L, 1L, 20L)

                    verify(exactly = 1) { memberRepository.delete(member) }
                }
            }

            context("사용자 정의 역할로 강퇴 권한을 받은 경우") {
                it("동등하거나 높은 역할의 멤버는 제거할 수 없다") {
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 20L) } returns
                        TeamMember(team = team, userId = 20L)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 30L) } returns
                        TeamMember(team = team, userId = 30L)
                    every { roleReader.getMemberRoles(1L, 20L) } returns listOf(role(1))
                    for (priority in listOf(1, 10)) {
                        every { roleReader.getMemberRoles(1L, 30L) } returns listOf(role(priority))

                        shouldThrow<ExpectedException> { service.execute(20L, 1L, 30L) }
                            .statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }

                it("낮은 역할의 멤버는 제거할 수 있다") {
                    val target = TeamMember(team = team, userId = 30L)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 20L) } returns
                        TeamMember(team = team, userId = 20L)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 30L) } returns target
                    every { roleReader.getMemberRoles(1L, 20L) } returns listOf(role(10))
                    every { roleReader.getMemberRoles(1L, 30L) } returns listOf(role(1))
                    every { memberRepository.delete(target) } just Runs

                    service.execute(20L, 1L, 30L)

                    verify(exactly = 1) { memberRepository.delete(target) }
                }

                it("기본 역할만 가진 동등한 멤버는 제거할 수 없다") {
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 20L) } returns
                        TeamMember(team = team, userId = 20L)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 30L) } returns
                        TeamMember(team = team, userId = 30L)
                    every { roleReader.getMemberRoles(1L, any()) } returns
                        listOf(role(Int.MIN_VALUE).copy(isDefault = true))

                    shouldThrow<ExpectedException> { service.execute(20L, 1L, 30L) }
                        .statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }

                it("사용자 정의 역할 우선순위가 높아도 내장 ADMIN은 제거할 수 없다") {
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 20L) } returns
                        TeamMember(team = team, userId = 20L)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 30L) } returns
                        TeamMember(team = team, userId = 30L, role = TeamRole.ADMIN)
                    every { roleReader.getMemberRoles(1L, 20L) } returns listOf(role(Int.MAX_VALUE))

                    shouldThrow<ExpectedException> { service.execute(20L, 1L, 30L) }
                        .statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }
            }

            context("ADMIN이 일반 멤버를 제거하면") {
                it("대상 멤버십을 삭제한다") {
                    val admin = TeamMember(team = team, userId = 20L, role = TeamRole.ADMIN)
                    val target = TeamMember(team = team, userId = 30L)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 20L) } returns admin
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 30L) } returns target
                    every { memberRepository.delete(target) } just Runs

                    service.execute(20L, 1L, 30L)

                    verify(exactly = 1) { memberRepository.delete(target) }
                }
            }

            context("OWNER를 탈퇴시키거나 제거하려 하면") {
                it("FORBIDDEN으로 거부한다") {
                    val owner = TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(1L, 10L) } returns owner

                    val error = shouldThrow<ExpectedException> { service.execute(10L, 1L, 10L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }
            }
        }
    })
