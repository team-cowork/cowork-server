package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.DmParticipantProjectionRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.sqlclient.Pool
import java.time.Instant
import kotlinx.coroutines.runBlocking

private const val CHANNEL_ID = 11L
private const val TEAM_ID = 42L
private const val USER_ID = 100L
private val REQUESTER = RequesterContext(userId = USER_ID, role = "MEMBER")
private val GLOBAL_ADMIN = RequesterContext(userId = USER_ID, role = "ADMIN")
private val VERSION = Instant.parse("2026-10-13T01:00:00Z")

private class ChannelGuardFixture {
    val channelRepository = mockk<ChannelLifecycleProjectionRepository>()
    val dmParticipantRepository = mockk<DmParticipantProjectionRepository>()
    val teamMemberRepository = mockk<TeamMemberProjectionRepository>()
    val policyRepository = mockk<ChannelRolePolicyRepository>()
    val accessReadiness = mockk<ProjectionReadinessView>()
    val managerReadiness = mockk<ProjectionReadinessView>()
    val pool = mockk<Pool>()
    val guard = ChannelAccessGuard(
        pool,
        channelRepository,
        dmParticipantRepository,
        teamMemberRepository,
        policyRepository,
        accessReadiness,
        managerReadiness,
    )

    fun ready(access: Boolean = true, manager: Boolean = true) {
        every { accessReadiness.isReady } returns access
        every { managerReadiness.isReady } returns manager
    }

    fun channel(teamId: Long? = TEAM_ID, isPrivate: Boolean? = false, deleted: Boolean = false, found: Boolean = true, type: String? = "TEXT") {
        coEvery { channelRepository.findForRead(pool, CHANNEL_ID) } returns
            if (found) ChannelLifecycleProjection(CHANNEL_ID, teamId, deleted, VERSION, isPrivate, type) else null
    }

    fun teamMember(role: String = "MEMBER", deleted: Boolean = false, found: Boolean = true) {
        coEvery { teamMemberRepository.findForRead(pool, TEAM_ID, USER_ID) } returns
            if (found) TeamMemberProjection(TEAM_ID, USER_ID, role, deleted, VERSION) else null
    }

    fun dmParticipant(active: Boolean) {
        coEvery { dmParticipantRepository.isActiveParticipant(pool, CHANNEL_ID, USER_ID) } returns active
    }

    fun messageRead(allowed: Boolean) {
        coEvery { policyRepository.canAccess(TEAM_ID, USER_ID, CHANNEL_ID, "message_read") } returns allowed
    }

    fun access() = runBlocking { guard.requireChannelAccess(CHANNEL_ID, USER_ID) }
}

class ChannelAccessGuardTest :
    DescribeSpec({
        lateinit var fixture: ChannelGuardFixture

        beforeEach { fixture = ChannelGuardFixture() }

        describe("ChannelAccessGuard 클래스의") {
            describe("requireChannelAccess 메서드는") {
                context("공개 팀 채널이고 팀 멤버이며 역할 정책이 메시지 읽기를 허용하면") {
                    it("채널 멤버가 아니어도 통과한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = false)
                        fixture.dmParticipant(false)
                        fixture.teamMember()
                        fixture.messageRead(true)

                        shouldNotThrowAny { fixture.access() }
                    }
                }

                context("음성 채널이면") {
                    it("message_read 대신 voice_connect로 접근을 허용하고 회수한다") {
                        fixture.ready()
                        fixture.channel(type = "VOICE")
                        fixture.teamMember()
                        coEvery { fixture.policyRepository.canAccess(TEAM_ID, USER_ID, CHANNEL_ID, "voice_connect") } returns true
                        shouldNotThrowAny { fixture.access() }
                        coEvery { fixture.policyRepository.canAccess(TEAM_ID, USER_ID, CHANNEL_ID, "voice_connect") } returns false
                        shouldThrow<ResourceAccessDeniedException> { fixture.access() }
                        coVerify(exactly = 0) { fixture.policyRepository.canAccess(any(), any(), any(), "message_read") }
                    }
                }

                context("팀 채널 종류가 아직 반영되지 않았으면") {
                    it("텍스트 채널로 추측하지 않고 준비되지 않음으로 처리한다") {
                        fixture.ready()
                        fixture.channel(type = null)
                        fixture.teamMember()
                        shouldThrow<ProjectionNotReadyException> { fixture.access() }
                    }
                }

                context("팀 OWNER이면") {
                    it("역할 정책을 조회하지 않고 통과한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = false)
                        fixture.dmParticipant(false)
                        fixture.teamMember(role = "OWNER")

                        shouldNotThrowAny { fixture.access() }

                        coVerify(exactly = 0) { fixture.policyRepository.canAccess(any(), any(), any(), any()) }
                    }
                }

                context("역할 정책이 메시지 읽기를 허용하지 않으면") {
                    it("팀 멤버여도 거부한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = false)
                        fixture.dmParticipant(false)
                        fixture.teamMember(role = "ADMIN")
                        fixture.messageRead(false)

                        shouldThrow<ResourceAccessDeniedException> { fixture.access() }
                    }
                }

                context("팀 멤버가 아니거나 탈퇴한 멤버이면") {
                    it("채널 멤버여도 거부한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = false)
                        fixture.dmParticipant(true)

                        listOf({ fixture.teamMember(found = false) }, { fixture.teamMember(deleted = true) })
                            .forEach { stub ->
                                stub()

                                shouldThrow<ResourceAccessDeniedException> { fixture.access() }
                            }
                    }
                }

                context("팀 멤버이지만 채널 멤버가 아닌 경우") {
                    it("비공개 채널도 OWNER 권한으로 접근한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = true)
                        fixture.teamMember(role = "OWNER")
                        fixture.dmParticipant(false)

                        shouldNotThrowAny { fixture.access() }
                    }

                    it("공개 채널이면 역할 정책 확인으로 넘어가 통과한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = false)
                        fixture.teamMember()
                        fixture.dmParticipant(false)
                        fixture.messageRead(true)

                        shouldNotThrowAny { fixture.access() }
                    }

                    it("공개 여부 대신 권한으로 접근을 판단한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = null)
                        fixture.teamMember(role = "OWNER")
                        fixture.dmParticipant(false)

                        shouldNotThrowAny { fixture.access() }
                    }
                }

                context("팀 멤버이면서 채널 멤버이면") {
                    it("공개 여부와 무관하게 OWNER 권한으로 통과한다") {
                        fixture.ready()
                        fixture.teamMember(role = "OWNER")
                        fixture.dmParticipant(true)

                        listOf(true, false, null).forEach { isPrivate ->
                            fixture.channel(isPrivate = isPrivate)

                            shouldNotThrowAny { fixture.access() }
                        }
                    }

                    it("공개 여부가 null이어도 역할 정책이 거부하면 거부한다") {
                        fixture.ready()
                        fixture.channel(isPrivate = null)
                        fixture.teamMember(role = "ADMIN")
                        fixture.dmParticipant(true)
                        fixture.messageRead(false)

                        shouldThrow<ResourceAccessDeniedException> { fixture.access() }
                    }
                }

                context("DM 채널이면") {
                    it("팀 멤버십을 보지 않고 채널 멤버일 때만 통과한다") {
                        fixture.ready()
                        fixture.channel(teamId = null)

                        fixture.dmParticipant(false)
                        shouldThrow<ResourceAccessDeniedException> { fixture.access() }

                        fixture.dmParticipant(true)
                        shouldNotThrowAny { fixture.access() }

                        coVerify(exactly = 0) { fixture.teamMemberRepository.findForRead(any(), any(), any()) }
                    }
                }

                context("채널이 삭제되었거나 알 수 없으면") {
                    it("멤버십을 조회하지 않고 거부한다") {
                        fixture.ready()

                        listOf({ fixture.channel(deleted = true) }, { fixture.channel(found = false) })
                            .forEach { stub ->
                                stub()

                                shouldThrow<ResourceAccessDeniedException> { fixture.access() }
                            }

                        coVerify(exactly = 0) { fixture.dmParticipantRepository.isActiveParticipant(any(), any(), any()) }
                    }
                }

                context("projection이 준비되지 않았으면") {
                    it("준비 안 됨 예외를 던지고 조회하지 않는다") {
                        fixture.ready(access = false)

                        shouldThrow<ProjectionNotReadyException> { fixture.access() }

                        coVerify(exactly = 0) { fixture.channelRepository.findForRead(any(), any()) }
                    }
                }
            }

            describe("requireSettingsManager 메서드는") {
                context("채널 소속 팀의 OWNER 또는 ADMIN이면") {
                    it("통과한다") {
                        fixture.ready()
                        fixture.channel()

                        listOf("OWNER", "ADMIN").forEach { role ->
                            fixture.teamMember(role = role)

                            shouldNotThrowAny {
                                runBlocking { fixture.guard.requireSettingsManager(CHANNEL_ID, REQUESTER) }
                            }
                        }
                    }
                }

                context("채널 소속 팀의 일반 MEMBER이면") {
                    it("거부한다") {
                        fixture.ready()
                        fixture.channel()
                        fixture.teamMember(role = "MEMBER")

                        shouldThrow<ResourceAccessDeniedException> {
                            runBlocking { fixture.guard.requireSettingsManager(CHANNEL_ID, REQUESTER) }
                        }
                    }
                }

                context("전역 ADMIN이어도 팀 OWNER·ADMIN이 아니면") {
                    it("소유 서비스와 같이 우회 없이 거부한다") {
                        fixture.ready()
                        fixture.channel()
                        fixture.teamMember(found = false)

                        shouldThrow<ResourceAccessDeniedException> {
                            runBlocking { fixture.guard.requireSettingsManager(CHANNEL_ID, GLOBAL_ADMIN) }
                        }
                    }
                }

                context("팀을 탈퇴한 OWNER이면") {
                    it("거부한다") {
                        fixture.ready()
                        fixture.channel()
                        fixture.teamMember(role = "OWNER", deleted = true)

                        shouldThrow<ResourceAccessDeniedException> {
                            runBlocking { fixture.guard.requireSettingsManager(CHANNEL_ID, REQUESTER) }
                        }
                    }
                }

                context("채널이 삭제되었거나 알 수 없거나 팀이 없는 DM이면") {
                    it("팀 역할을 조회하지 않고 거부한다") {
                        fixture.ready()

                        listOf(
                            { fixture.channel(deleted = true) },
                            { fixture.channel(found = false) },
                            { fixture.channel(teamId = null) },
                        ).forEach { stub ->
                            stub()

                            shouldThrow<ResourceAccessDeniedException> {
                                runBlocking { fixture.guard.requireSettingsManager(CHANNEL_ID, REQUESTER) }
                            }
                        }

                        coVerify(exactly = 0) { fixture.teamMemberRepository.findForRead(any(), any(), any()) }
                    }
                }

                context("projection이 준비되지 않았으면") {
                    it("준비 안 됨 예외를 던지고 조회하지 않는다") {
                        fixture.ready(manager = false)

                        shouldThrow<ProjectionNotReadyException> {
                            runBlocking { fixture.guard.requireSettingsManager(CHANNEL_ID, REQUESTER) }
                        }

                        coVerify(exactly = 0) { fixture.channelRepository.findForRead(any(), any()) }
                    }
                }
            }
        }
    })
