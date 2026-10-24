package com.cowork.team.domain.teamMember.entity

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.temporal.ChronoUnit

class TeamMemberEventStateTest :
    DescribeSpec({
        lateinit var team: Team
        lateinit var member: TeamMember
        val joinedAt = Instant.parse("2026-10-25T00:00:00Z")

        beforeEach {
            team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)
            member = TeamMember(team = team, userId = 20L)
        }

        describe("TeamMemberEventState 클래스의") {
            describe("create 메서드는") {
                context("새 멤버십 이벤트를 만드는 경우") {
                    it("최초 원본 버전과 같은 권한 버전을 기록한다") {
                        val state = TeamMemberEventState.create(team, member, false, joinedAt)

                        state.authorizationVersion shouldBe joinedAt
                        state.authorizationStateVersion shouldBe joinedAt
                        state.stateOccurredAt shouldBe joinedAt
                    }
                }
            }

            describe("apply 메서드는") {
                context("닉네임과 팀 이름만 변경되는 경우") {
                    it("원본 버전을 갱신하고 권한 버전은 유지한다") {
                        val state = TeamMemberEventState.create(team, member, false, joinedAt)
                        member.changeNickname("nickname")
                        team.update("renamed", null, null)

                        state.apply(team, member, false, joinedAt.plusSeconds(1))

                        state.nickname shouldBe "nickname"
                        state.teamName shouldBe "renamed"
                        state.authorizationVersion shouldBe joinedAt
                        state.authorizationStateVersion shouldBe joinedAt.plusSeconds(1)
                        state.stateOccurredAt shouldBe joinedAt.plusSeconds(1)
                    }
                }

                context("기본 역할이 변경되는 경우") {
                    it("역할 승격과 강등마다 이전 접근 권한을 구분할 새 버전을 기록한다") {
                        val state = TeamMemberEventState.create(team, member, false, joinedAt)
                        member.changeRole(TeamRole.ADMIN)
                        state.apply(team, member, false, joinedAt.plusSeconds(1))
                        state.authorizationVersion shouldBe joinedAt.plusSeconds(1)

                        member.changeRole(TeamRole.MEMBER)
                        state.apply(team, member, false, joinedAt.plusSeconds(2))
                        state.authorizationVersion shouldBe joinedAt.plusSeconds(2)
                        state.authorizationStateVersion shouldBe state.stateOccurredAt
                    }
                }

                context("멤버가 탈퇴하고 재가입하는 경우") {
                    it("요청 시각이 같아도 탈퇴와 재가입의 권한 버전을 구분한다") {
                        val state = TeamMemberEventState.create(team, member, false, joinedAt)

                        state.apply(team, member, true, joinedAt)
                        state.authorizationVersion shouldBe joinedAt.plus(1, ChronoUnit.MICROS)
                        state.apply(team, member, false, joinedAt)

                        state.deleted shouldBe false
                        state.authorizationVersion shouldBe joinedAt.plus(2, ChronoUnit.MICROS)
                        state.authorizationStateVersion shouldBe state.stateOccurredAt
                    }
                }

                context("구형 writer가 원본 버전만 바꾼 뒤 닉네임을 변경하는 경우") {
                    it("누락된 권한 변경 가능성을 보수적으로 차단하고 이후 원본 변경을 추적한다") {
                        val state = TeamMemberEventState.create(team, member, false, joinedAt)
                        state.stateOccurredAt = joinedAt.plusSeconds(1)
                        member.changeNickname("nickname")

                        state.apply(team, member, false, joinedAt.plusSeconds(2))

                        state.authorizationVersion shouldBe joinedAt.plusSeconds(1)
                        state.authorizationStateVersion shouldBe joinedAt.plusSeconds(2)
                    }
                }
            }

            describe("normalizeAuthorizationVersion 메서드는") {
                context("권한 버전이 없는 구형 상태인 경우") {
                    it("원본 버전을 바꾸지 않고 권한 버전을 보정한다") {
                        val state = TeamMemberEventState(
                            teamId = team.id,
                            userId = member.userId,
                            role = member.role,
                            teamName = team.name,
                            nickname = null,
                            deleted = false,
                            stateOccurredAt = joinedAt,
                        )

                        state.normalizeAuthorizationVersion() shouldBe joinedAt

                        state.authorizationVersion shouldBe joinedAt
                        state.authorizationStateVersion shouldBe joinedAt
                        state.stateOccurredAt shouldBe joinedAt
                    }
                }

                context("구형 writer가 기존 권한 버전을 남겨 놓은 경우") {
                    it("스냅샷에도 최신 원본 버전을 보수적인 권한 버전으로 발행한다") {
                        val state = TeamMemberEventState.create(team, member, false, joinedAt)
                        state.stateOccurredAt = joinedAt.plusSeconds(1)

                        state.normalizeAuthorizationVersion() shouldBe joinedAt.plusSeconds(1)
                        state.normalizeAuthorizationVersion() shouldBe joinedAt.plusSeconds(1)

                        state.authorizationStateVersion shouldBe joinedAt.plusSeconds(1)
                        state.stateOccurredAt shouldBe joinedAt.plusSeconds(1)
                    }
                }
            }
        }
    })
