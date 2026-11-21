package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.projection.NicknameFormatGuide
import com.cowork.team.domain.team.projection.TeamSettingProjectionReader
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class CreateTeamMemberServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var publisher: TeamMemberEventPublisher
        lateinit var settingReader: TeamSettingProjectionReader
        lateinit var service: CreateTeamMemberServiceImpl

        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            settingReader = mockk()
            publisher = mockk(relaxed = true)
            service = CreateTeamMemberServiceImpl(
                teamRepository,
                memberRepository,
                publisher,
                settingReader,
            )
            every { settingReader.getNicknameFormat(1L) } returns NicknameFormatGuide(enforced = false, example = null)
        }

        describe("CreateTeamMemberServiceImpl 클래스의 execute 메서드는") {
            context("아직 팀원이 아니면") {
                it("MEMBER 역할로 팀에 가입시킨다") {
                    val joinedAt = LocalDateTime.now()
                    val member = TeamMember(team = team, userId = 99L).also { it.joinedAt = joinedAt }
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every { memberRepository.existsByTeamIdAndUserId(1L, 99L) } returns false
                    every { memberRepository.save(any()) } returns member

                    val result = service.execute(1L, 99L)

                    result.teamId shouldBe 1L
                    result.userId shouldBe 99L
                    result.role shouldBe "MEMBER"
                    result.joinedAt shouldBe joinedAt
                    result.nicknameFormatEnforced shouldBe false
                    verifyOrder {
                        teamRepository.findByIdForUpdate(1L)
                        memberRepository.existsByTeamIdAndUserId(1L, 99L)
                        memberRepository.save(match { it.team == team && it.userId == 99L })
                        publisher.publishUpsert(member, any())
                    }
                }
            }

            context("닉네임 형식 안내가 켜진 팀에 가입하면") {
                it("가입 응답에 닉네임 형식 예시를 포함한다") {
                    val member = TeamMember(team = team, userId = 99L).also { it.joinedAt = LocalDateTime.now() }
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every { memberRepository.existsByTeamIdAndUserId(1L, 99L) } returns false
                    every { memberRepository.save(any()) } returns member
                    every { settingReader.getNicknameFormat(1L) } returns
                        NicknameFormatGuide(enforced = true, example = "홍길동 / 프론트엔드")

                    val result = service.execute(1L, 99L)

                    result.nicknameFormatEnforced shouldBe true
                    result.nicknameFormatExample shouldBe "홍길동 / 프론트엔드"
                }
            }

            context("이미 팀 멤버이면") {
                it("CONFLICT로 중복 가입을 거부한다") {
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every { memberRepository.existsByTeamIdAndUserId(1L, 99L) } returns true

                    val error = shouldThrow<ExpectedException> { service.execute(1L, 99L) }

                    error.statusCode shouldBe HttpStatus.CONFLICT
                    verify(exactly = 0) { memberRepository.save(any()) }
                }
            }

            context("팀이 삭제됐으면") {
                it("NOT_FOUND로 거부하고 멤버를 저장하지 않는다") {
                    every { teamRepository.findByIdForUpdate(1L) } returns null

                    val error = shouldThrow<ExpectedException> { service.execute(1L, 99L) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { memberRepository.save(any()) }
                }
            }
        }
    })
