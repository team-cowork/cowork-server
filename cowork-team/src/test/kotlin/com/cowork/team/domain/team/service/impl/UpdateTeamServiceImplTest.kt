package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipService
import com.cowork.team.domain.team.service.TeamPermissionGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class UpdateTeamServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var ownership: TeamIconOwnershipService
        lateinit var teamEventPublisher: TeamEventPublisher
        lateinit var teamMemberEventPublisher: TeamMemberEventPublisher
        lateinit var service: UpdateTeamServiceImpl

        fun team() = Team(
            id = 1L,
            name = "before",
            description = "keep",
            iconUrl = "old-icon",
            ownerId = 10L,
        ).also {
            it.createdAt = LocalDateTime.now()
            it.updatedAt = LocalDateTime.now()
        }

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            ownership = mockk()
            teamEventPublisher = mockk(relaxed = true)
            teamMemberEventPublisher = mockk(relaxed = true)
            every { ownership.replace(any(), any(), any()) } answers {
                secondArg<Team>().iconUrl = thirdArg<String>()
            }
            service = UpdateTeamServiceImpl(
                ownership,
                memberRepository,
                teamEventPublisher,
                teamMemberEventPublisher,
                TeamAccessGuard(
                    teamRepository,
                    memberRepository,
                    TeamPermissionGuard(memberRepository, mockk(relaxed = true)),
                ),
            )
        }

        describe("UpdateTeamServiceImpl 클래스의 execute 메서드는") {
            context("일반 멤버가 팀 수정을 요청하면") {
                it("FORBIDDEN으로 거부하고 기존 값을 유지한다") {
                    val team = team()
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserId(1L, 20L)
                    } returns null

                    val error = shouldThrow<ExpectedException> {
                        service.execute(20L, 1L, UpdateTeamRequest("after", null, null))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    team.name shouldBe "before"
                }
            }

            context("ADMIN이 일부 필드를 수정하면") {
                it("요청한 필드만 변경한다") {
                    val team = team()
                    val admin = TeamMember(team = team, userId = 20L, role = TeamRole.ADMIN)
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserId(1L, 20L)
                    } returns admin
                    every { memberRepository.findAllByTeamIdForUpdate(1L) } returns listOf(admin)

                    val result = service.execute(20L, 1L, UpdateTeamRequest("after", null, "new-icon"))

                    result.name shouldBe "after"
                    result.description shouldBe "keep"
                    result.iconUrl shouldBe "new-icon"
                }
            }

            context("일반 멤버가 잘못된 이름으로 수정을 요청하면") {
                it("이름 검증보다 권한 확인을 먼저 수행해 FORBIDDEN으로 거부한다") {
                    val team = team()
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every { memberRepository.findByTeamIdAndUserId(1L, 20L) } returns null

                    val error = shouldThrow<ExpectedException> {
                        service.execute(20L, 1L, UpdateTeamRequest("", null, null))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("이름이 유효하지 않으면") {
                mapOf(
                    "빈 문자열" to "",
                    "공백" to "   ",
                    "공백 문자" to "\t\n",
                    "101자" to "가".repeat(101),
                    "이모지 101자" to "😀".repeat(101),
                ).forEach { (label, name) ->
                    it("$label 이름을 BAD_REQUEST로 거부하고 쓰기 작업을 하지 않는다") {
                        val team = team()
                        val admin = TeamMember(team = team, userId = 20L, role = TeamRole.ADMIN)
                        every { teamRepository.findByIdForUpdate(1L) } returns team
                        every { memberRepository.findByTeamIdAndUserId(1L, 20L) } returns admin

                        val error = shouldThrow<ExpectedException> {
                            service.execute(20L, 1L, UpdateTeamRequest(name, "changed", "new-icon"))
                        }

                        error.statusCode shouldBe HttpStatus.BAD_REQUEST
                        team.name shouldBe "before"
                        team.description shouldBe "keep"
                        team.iconUrl shouldBe "old-icon"
                        verify(exactly = 0) { ownership.replace(any(), any(), any()) }
                        verify(exactly = 0) { teamEventPublisher.publishUpdated(any(), any(), any()) }
                        verify(exactly = 0) { teamMemberEventPublisher.publishUpsert(any(), any()) }
                    }
                }
            }

            context("이름이 유효하면") {
                mapOf(
                    "1자" to "가",
                    "100자" to "가".repeat(100),
                    "이모지 100자" to "😀".repeat(100),
                    "중간 공백" to "개발 팀",
                    "앞뒤 공백" to " 개발 팀 ",
                ).forEach { (label, name) ->
                    it("$label 이름을 입력값 그대로 변경한다") {
                        val team = team()
                        val admin = TeamMember(team = team, userId = 20L, role = TeamRole.ADMIN)
                        every { teamRepository.findByIdForUpdate(1L) } returns team
                        every { memberRepository.findByTeamIdAndUserId(1L, 20L) } returns admin
                        every { memberRepository.findAllByTeamIdForUpdate(1L) } returns listOf(admin)

                        val result = service.execute(20L, 1L, UpdateTeamRequest(name, null, null))

                        result.name shouldBe name
                    }
                }
            }

            context("이름을 null로 요청하면") {
                it("기존 이름을 유지하고 함께 요청한 필드만 변경한다") {
                    val team = team()
                    val admin = TeamMember(team = team, userId = 20L, role = TeamRole.ADMIN)
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every { memberRepository.findByTeamIdAndUserId(1L, 20L) } returns admin
                    every { memberRepository.findAllByTeamIdForUpdate(1L) } returns listOf(admin)

                    val result = service.execute(20L, 1L, UpdateTeamRequest(null, "changed", null))

                    result.name shouldBe "before"
                    result.description shouldBe "changed"
                }
            }
        }
    })
