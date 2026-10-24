package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamPermissionGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeNicknameRequest
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ChangeTeamMemberNicknameServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var roleReader: TeamRoleProjectionReader
        lateinit var eventPublisher: TeamMemberEventPublisher
        lateinit var service: ChangeTeamMemberNicknameServiceImpl

        val team = Team(id = 5L, name = "team", description = null, iconUrl = null, ownerId = 1L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            roleReader = mockk()
            eventPublisher = mockk(relaxed = true)
            service = ChangeTeamMemberNicknameServiceImpl(
                memberRepository,
                eventPublisher,
                TeamMemberAccessGuard(
                    teamRepository,
                    memberRepository,
                    TeamPermissionGuard(memberRepository, roleReader),
                ),
                TeamRoleAccessGuard(teamRepository, memberRepository, roleReader),
                roleReader,
            )
            every { teamRepository.findByIdForUpdate(5L) } returns team
        }

        fun stubActor(member: TeamMember) {
            every { memberRepository.findByTeamIdAndUserId(5L, member.userId) } returns member
        }

        fun stubTarget(member: TeamMember) {
            every { memberRepository.findByTeamIdAndUserIdForUpdate(5L, member.userId) } returns member
        }

        describe("ChangeTeamMemberNicknameServiceImpl 클래스의 execute 메서드는") {
            context("멤버가 자신의 닉네임을 바꾸면") {
                it("앞뒤 공백을 제거해 저장하고 멤버 상태를 발행한다") {
                    val member = TeamMember(team = team, userId = 7L)
                    stubTarget(member)

                    service.execute(7L, 5L, 7L, ChangeNicknameRequest("  홍길동 / 프론트엔드  "))

                    member.nickname shouldBe "홍길동 / 프론트엔드"
                    verify(exactly = 1) { eventPublisher.publishUpsert(member, any()) }
                    verify(exactly = 0) { roleReader.getMemberRoles(any(), any()) }
                }
            }

            context("빈 닉네임을 보내면") {
                it("팀 닉네임을 지운다") {
                    val member = TeamMember(team = team, userId = 7L).also { it.changeNickname("이전 닉네임") }
                    stubTarget(member)

                    service.execute(7L, 5L, 7L, ChangeNicknameRequest("   "))

                    member.nickname shouldBe null
                }
            }

            context("닉네임이 50자를 넘으면") {
                it("BAD_REQUEST로 거부한다") {
                    val member = TeamMember(team = team, userId = 7L)
                    stubTarget(member)

                    val error = shouldThrow<ExpectedException> {
                        service.execute(7L, 5L, 7L, ChangeNicknameRequest("가".repeat(51)))
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    member.nickname shouldBe null
                }
            }

            context("닉네임 관리 권한이 없는 멤버가 다른 멤버의 닉네임을 바꾸면") {
                it("FORBIDDEN으로 거부한다") {
                    val target = TeamMember(team = team, userId = 8L)
                    stubActor(TeamMember(team = team, userId = 7L))
                    stubTarget(target)
                    every { roleReader.getMemberRoles(5L, 7L) } returns listOf(role(priority = 10))

                    val error = shouldThrow<ExpectedException> {
                        service.execute(7L, 5L, 8L, ChangeNicknameRequest("새 닉네임"))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    target.nickname shouldBe null
                }
            }

            context("닉네임 관리 권한을 가진 멤버가 자신보다 낮은 순위의 멤버 닉네임을 바꾸면") {
                it("대상 멤버의 닉네임을 변경한다") {
                    val target = TeamMember(team = team, userId = 8L)
                    stubActor(TeamMember(team = team, userId = 7L))
                    stubTarget(target)
                    every { roleReader.getMemberRoles(5L, 7L) } returns
                        listOf(role(priority = 10, manageNicknames = true))
                    every { roleReader.getMemberRoles(5L, 8L) } returns listOf(role(priority = 3))

                    service.execute(7L, 5L, 8L, ChangeNicknameRequest("새 닉네임"))

                    target.nickname shouldBe "새 닉네임"
                }
            }

            context("닉네임 관리 권한을 가진 멤버가 같은 순위의 멤버 닉네임을 바꾸면") {
                it("FORBIDDEN으로 거부한다") {
                    val target = TeamMember(team = team, userId = 8L)
                    stubActor(TeamMember(team = team, userId = 7L))
                    stubTarget(target)
                    every { roleReader.getMemberRoles(5L, 7L) } returns
                        listOf(role(priority = 10, manageNicknames = true))
                    every { roleReader.getMemberRoles(5L, 8L) } returns listOf(role(priority = 10))

                    val error = shouldThrow<ExpectedException> {
                        service.execute(7L, 5L, 8L, ChangeNicknameRequest("새 닉네임"))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    target.nickname shouldBe null
                }
            }

            context("닉네임 관리 권한을 가진 멤버가 ADMIN의 닉네임을 바꾸면") {
                it("FORBIDDEN으로 거부한다") {
                    val target = TeamMember(team = team, userId = 8L, role = TeamRole.ADMIN)
                    stubActor(TeamMember(team = team, userId = 7L))
                    stubTarget(target)
                    every { roleReader.getMemberRoles(5L, 7L) } returns
                        listOf(role(priority = 10, manageNicknames = true))
                    every { roleReader.getMemberRoles(5L, 8L) } returns emptyList()

                    val error = shouldThrow<ExpectedException> {
                        service.execute(7L, 5L, 8L, ChangeNicknameRequest("새 닉네임"))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("ADMIN이 멤버의 닉네임을 바꾸면") {
                it("역할 순위와 무관하게 변경한다") {
                    val target = TeamMember(team = team, userId = 8L)
                    stubActor(TeamMember(team = team, userId = 7L, role = TeamRole.ADMIN))
                    stubTarget(target)

                    service.execute(7L, 5L, 8L, ChangeNicknameRequest("새 닉네임"))

                    target.nickname shouldBe "새 닉네임"
                    verify(exactly = 0) { roleReader.getMemberRoles(any(), any()) }
                }
            }

            context("ADMIN이 OWNER의 닉네임을 바꾸면") {
                it("FORBIDDEN으로 거부한다") {
                    val owner = TeamMember(team = team, userId = 1L, role = TeamRole.OWNER)
                    stubActor(TeamMember(team = team, userId = 7L, role = TeamRole.ADMIN))
                    stubTarget(owner)

                    val error = shouldThrow<ExpectedException> {
                        service.execute(7L, 5L, 1L, ChangeNicknameRequest("새 닉네임"))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    owner.nickname shouldBe null
                }
            }
        }
    }) {
    companion object {
        private fun role(priority: Int, manageNicknames: Boolean = false) = TeamRoleResponse(
            id = priority.toLong(),
            teamId = 5L,
            name = "role-$priority",
            colorHex = "#123ABC",
            priority = priority,
            mentionable = true,
            permissions = mapOf(TeamRolePermissionSchema.MANAGE_NICKNAMES to manageNicknames),
            createdAt = null,
            updatedAt = null,
        )
    }
}
