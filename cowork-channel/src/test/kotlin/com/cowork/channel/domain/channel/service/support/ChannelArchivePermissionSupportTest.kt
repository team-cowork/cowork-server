package com.cowork.channel.domain.channel.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.domain.project.entity.ProjectAccessProjection
import com.cowork.channel.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import com.cowork.channel.global.projection.ProjectionStreams
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Optional

class ChannelArchivePermissionSupportTest :
    DescribeSpec({

        val authorizationVersion = Instant.parse("2026-11-01T00:00:00Z")
        val streams = ProjectionStreams()

        lateinit var membershipRepository: TeamMembershipRepository
        lateinit var evaluator: ChannelMessageReadPolicyEvaluator
        lateinit var projectAccessRepository: ProjectAccessProjectionRepository
        lateinit var readinessGate: ProjectionReadinessGate
        lateinit var support: ChannelArchivePermissionSupport

        fun channel(type: ChannelType = ChannelType.TEXT, teamId: Long? = 10L, projectId: Long? = 5L) = Channel(
            id = 1L,
            teamId = teamId,
            projectId = projectId,
            name = "ch",
            type = type,
            viewType = ChannelViewType.TEXT,
            description = null,
            createdBy = 1L,
            dmKey = if (type == ChannelType.DM) "1:2" else null,
        )

        fun member(role: String = "MEMBER") =
            TeamMembership(teamId = 10L, userId = 7L, role = role, authorizationVersion = authorizationVersion)

        fun access(canChannelArchive: Boolean, sourceAuthorizationVersion: Instant = authorizationVersion) =
            ProjectAccessProjection(
                accessKey = "5:7",
                projectId = 5L,
                userId = 7L,
                canRead = true,
                canChannelArchive = canChannelArchive,
                sourceOccurredAt = Instant.parse("2026-11-10T00:00:00Z"),
                sourceMembershipVersion = authorizationVersion,
                sourceAuthorizationVersion = sourceAuthorizationVersion,
            )

        fun statusOf(channel: Channel): HttpStatus =
            shouldThrow<ExpectedException> { support.requireArchivePermission(channel, 7L) }.statusCode as HttpStatus

        beforeEach {
            membershipRepository = mockk()
            readinessGate = mockk(relaxed = true)
            // 실제 평가기의 멤버십·우회 판단을 쓰고, 채널 읽기와 팀 권한 판단만 대체한다.
            evaluator = spyk(
                ChannelMessageReadPolicyEvaluator(
                    membershipRepository,
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    readinessGate,
                    mockk(),
                ),
            )
            every { evaluator.requireChannelAccess(10L, 7L, any()) } just runs
            projectAccessRepository = mockk()
            support = ChannelArchivePermissionSupport(
                ChannelAccessGuard(mockk<ChannelRepository>()),
                evaluator,
                projectAccessRepository,
                readinessGate,
                streams,
            )
        }

        describe("ChannelArchivePermissionSupport 클래스의 requireArchivePermission 메서드는") {
            context("DM이나 음성 채널이면") {
                it("BAD_REQUEST로 거부한다") {
                    statusOf(channel(type = ChannelType.DM, teamId = null, projectId = null)) shouldBe
                        HttpStatus.BAD_REQUEST
                    statusOf(channel(type = ChannelType.VOICE)) shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("요청자가 채널을 읽을 수 없으면") {
                it("보관 권한을 확인하기 전에 거부한다") {
                    every { evaluator.requireChannelAccess(10L, 7L, any()) } throws
                        ExpectedException("채널 메시지 읽기 권한이 없습니다.", HttpStatus.FORBIDDEN)

                    statusOf(channel()) shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { projectAccessRepository.findById(any()) }
                }
            }

            context("프로젝트가 없는 채널이면") {
                it("팀 권한 archive_channels를 요구한다") {
                    every { evaluator.requireTeamPermission(10L, 7L, "archive_channels") } just runs

                    shouldNotThrowAny { support.requireArchivePermission(channel(projectId = null), 7L) }

                    verify { evaluator.requireTeamPermission(10L, 7L, "archive_channels") }
                    verify(exactly = 0) { projectAccessRepository.findById(any()) }
                }
            }

            context("프로젝트 채널이고 해당 프로젝트의 채널 보관 권한이 있으면") {
                it("project access stream을 확인한 뒤 허용한다") {
                    every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member()
                    every { projectAccessRepository.findById("5:7") } returns
                        Optional.of(access(canChannelArchive = true))

                    shouldNotThrowAny { support.requireArchivePermission(channel(), 7L) }

                    verify { readinessGate.requireCaughtUp(setOf(streams.projectAccess)) }
                    verify(exactly = 0) { evaluator.requireTeamPermission(any(), any(), any()) }
                }
            }

            context("프로젝트 채널 보관 권한이 없거나 권한 버전이 오래되면") {
                it("FORBIDDEN으로 거부한다") {
                    every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member()

                    every { projectAccessRepository.findById("5:7") } returns Optional.empty()
                    statusOf(channel()) shouldBe HttpStatus.FORBIDDEN

                    every { projectAccessRepository.findById("5:7") } returns
                        Optional.of(access(canChannelArchive = false))
                    statusOf(channel()) shouldBe HttpStatus.FORBIDDEN

                    every { projectAccessRepository.findById("5:7") } returns Optional.of(
                        access(
                            canChannelArchive = true,
                            sourceAuthorizationVersion = authorizationVersion.minusSeconds(1),
                        ),
                    )
                    statusOf(channel()) shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("요청자가 팀 OWNER나 ADMIN이면") {
                it("프로젝트 권한과 project access stream 상태를 확인하지 않고 허용한다") {
                    listOf("OWNER", "ADMIN").forEach { role ->
                        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member(role)

                        shouldNotThrowAny { support.requireArchivePermission(channel(), 7L) }
                    }

                    verify(exactly = 0) { readinessGate.requireCaughtUp(any()) }
                    verify(exactly = 0) { projectAccessRepository.findById(any()) }
                }
            }
        }
    })
