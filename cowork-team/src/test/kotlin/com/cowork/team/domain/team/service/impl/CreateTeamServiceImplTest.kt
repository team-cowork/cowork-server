package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamIconOwnershipService
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

class CreateTeamServiceImplTest :
    DescribeSpec({
        lateinit var savedTeam: Team
        lateinit var savedOwner: TeamMember
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var ownership: TeamIconOwnershipService
        lateinit var teamEventPublisher: TeamEventPublisher
        lateinit var teamMemberEventPublisher: TeamMemberEventPublisher
        lateinit var service: CreateTeamServiceImpl

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            teamEventPublisher = mockk(relaxed = true)
            teamMemberEventPublisher = mockk(relaxed = true)
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
            ownership = mockk()
            every { ownership.replace(any(), any(), any()) } answers {
                secondArg<Team>().iconUrl = thirdArg<String>()
            }
            service = CreateTeamServiceImpl(
                ownership,
                teamRepository,
                memberRepository,
                teamEventPublisher,
                teamMemberEventPublisher,
            )
        }

        describe("CreateTeamServiceImpl 클래스의 execute 메서드는") {
            context("사용자가 팀을 만들면") {
                it("요청 내용을 가진 팀과 OWNER 멤버를 생성한다") {
                    val result = service.execute(
                        7L,
                        CreateTeamRequest("cowork", "core team", "https://example.com/icon.png"),
                    )

                    result.name shouldBe "cowork"
                    result.description shouldBe "core team"
                    result.iconUrl shouldBe "https://example.com/icon.png"
                    result.ownerId shouldBe 7L
                    savedTeam.ownerId shouldBe 7L
                    savedOwner.team shouldBe savedTeam
                    savedOwner.userId shouldBe 7L
                    savedOwner.role shouldBe TeamRole.OWNER
                }
            }

            context("이름이 유효하지 않으면") {
                mapOf(
                    "빈 문자열" to "",
                    "공백" to "   ",
                    "공백 문자" to "\t\n",
                    "101자" to "가".repeat(101),
                    "이모지 101자" to "😀".repeat(101),
                    "이모지 101자" to "😀".repeat(101),
                ).forEach { (label, name) ->
                    it("$label 이름을 BAD_REQUEST로 거부하고 쓰기 작업을 하지 않는다") {
                        val error = shouldThrow<ExpectedException> {
                            service.execute(7L, CreateTeamRequest(name, null, "https://example.com/icon.png"))
                        }

                        error.statusCode shouldBe HttpStatus.BAD_REQUEST
                        verify(exactly = 0) { teamRepository.save(any()) }
                        verify(exactly = 0) { ownership.replace(any(), any(), any()) }
                        verify(exactly = 0) { memberRepository.save(any()) }
                        verify(exactly = 0) { teamEventPublisher.publishCreated(any(), any(), any()) }
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
                    it("$label 이름을 입력값 그대로 저장한다") {
                        val result = service.execute(7L, CreateTeamRequest(name, null, null))

                        result.name shouldBe name
                        savedTeam.name shouldBe name
                    }
                }
            }
        }
    })
