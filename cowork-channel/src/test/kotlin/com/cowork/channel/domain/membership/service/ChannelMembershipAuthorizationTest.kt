package com.cowork.channel.domain.membership.service

import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.consumer.ChannelLifecycleHandler
import com.cowork.channel.global.consumer.PreferenceTeamRoleProjectionHandler
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

class ChannelMembershipAuthorizationTest :
    DescribeSpec({
        lateinit var members: TeamMembershipRepository
        lateinit var roles: PreferenceTeamRoleProjectionHandler
        lateinit var handler: ChannelLifecycleHandler
        lateinit var member: TeamMembership
        val version = Instant.parse("2026-10-25T00:00:00Z")

        beforeEach {
            members = mockk()
            roles = mockk(relaxed = true)
            handler = ChannelLifecycleHandler(mockk(), members, mockk(), roles, mockk())
            member = TeamMembership(
                teamId = 10L,
                userId = 7L,
                role = "MEMBER",
                sourceOccurredAt = version,
                authorizationVersion = version.minusSeconds(1),
            )
            every { members.findStateByTeamIdAndUserIdForUpdate(10L, 7L) } answers { member }
            every { members.save(any()) } answers { firstArg() }
        }

        describe("ChannelLifecycleHandler 클래스의 멤버 권한 처리는") {
            context("현재보다 오래되거나 같은 원본 버전으로 관리자 승격을 요청하면") {
                it("현재 역할과 권한 버전을 유지한다") {
                    listOf(version.minusSeconds(1), version).forEach { incomingVersion ->
                        handler.onMemberUpsert(10L, 7L, "ADMIN", incomingVersion, incomingVersion)
                    }

                    member.role shouldBe "MEMBER"
                    member.authorizationVersion shouldBe version.minusSeconds(1)
                    verify(exactly = 0) { members.save(any()) }
                }
            }

            context("탈퇴 상태와 같은 원본 버전으로 가입을 요청하면") {
                it("멤버십과 관리자 접근을 복구하지 않는다") {
                    member.active = false

                    handler.onMemberUpsert(10L, 7L, "ADMIN", version, version)

                    member.active shouldBe false
                    member.role shouldBe "MEMBER"
                    verify(exactly = 0) { members.save(any()) }
                }
            }

            context("동일한 활성 멤버십의 권한 버전을 보정하면") {
                it("누락된 버전은 채우지만 구형 재전송으로 삭제하거나 낮추지 않는다") {
                    member.authorizationVersion = null

                    handler.onMemberUpsert(10L, 7L, "MEMBER", version, version)
                    handler.onMemberUpsert(10L, 7L, "MEMBER", version)
                    handler.onMemberUpsert(10L, 7L, "MEMBER", version, version.minusSeconds(1))

                    member.authorizationVersion shouldBe version
                    member.sourceOccurredAt shouldBe version
                    member.role shouldBe "MEMBER"
                }
            }

            context("더 새로운 구형 이벤트가 관리자를 일반 멤버로 강등하면") {
                it("기존 권한 버전을 지우고 새 원본 버전으로 판정하도록 한다") {
                    member.role = "ADMIN"

                    handler.onMemberUpsert(10L, 7L, "MEMBER", version.plusSeconds(1))

                    member.role shouldBe "MEMBER"
                    member.sourceOccurredAt shouldBe version.plusSeconds(1)
                    member.authorizationVersion shouldBe null
                }
            }

            context("같은 원본 버전에 가입과 탈퇴가 충돌하면") {
                it("탈퇴를 우선하며 이전 활성 멤버십의 권한 버전을 물려받지 않는다") {
                    handler.onMemberRemovedFromTeam(10L, 7L, "MEMBER", version)

                    member.active shouldBe false
                    member.authorizationVersion shouldBe null
                    verify {
                        roles.handle(match { it.eventType == "MEMBER_ASSIGNMENTS_DELETED" && it.occurredAt == version })
                    }
                }
            }

            context("재가입보다 오래된 탈퇴가 늦게 도착하면") {
                it("현재 멤버십은 유지하면서 이전 역할의 복구만 차단한다") {
                    val rejoinedAt = version.plusSeconds(1)
                    member.sourceOccurredAt = rejoinedAt
                    member.authorizationVersion = rejoinedAt

                    handler.onMemberRemovedFromTeam(10L, 7L, "MEMBER", version, version)

                    member.active shouldBe true
                    member.sourceOccurredAt shouldBe rejoinedAt
                    member.authorizationVersion shouldBe rejoinedAt
                    verify(exactly = 0) { members.save(any()) }
                    verify {
                        roles.handle(match { it.eventType == "MEMBER_ASSIGNMENTS_DELETED" && it.occurredAt == version })
                    }
                }
            }
        }
    })
