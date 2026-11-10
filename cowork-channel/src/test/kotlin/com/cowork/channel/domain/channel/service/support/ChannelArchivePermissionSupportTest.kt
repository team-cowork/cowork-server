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
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Optional

class ChannelArchivePermissionSupportTest {

    private val evaluator = mockk<ChannelMessageReadPolicyEvaluator>()
    private val membershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessRepository = mockk<ProjectAccessProjectionRepository>()
    private val readinessGate = mockk<ProjectionReadinessGate>(relaxed = true)

    private val support = ChannelArchivePermissionSupport(
        ChannelAccessGuard(mockk<ChannelRepository>()),
        evaluator,
        membershipRepository,
        projectAccessRepository,
        readinessGate,
    )

    private val authorizationVersion = Instant.parse("2026-11-01T00:00:00Z")

    @BeforeEach
    fun allowChannelRead() {
        every { evaluator.requireChannelAccess(10L, 7L, any()) } just runs
    }

    private fun channel(type: ChannelType = ChannelType.TEXT, teamId: Long? = 10L, projectId: Long? = 5L) = Channel(
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

    private fun member(role: String = "MEMBER") = TeamMembership(
        teamId = 10L,
        userId = 7L,
        role = role,
        authorizationVersion = authorizationVersion,
    )

    private fun access(canChannelArchive: Boolean, sourceAuthorizationVersion: Instant? = authorizationVersion) =
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

    private fun assertStatus(status: HttpStatus, channel: Channel) {
        val ex = assertThrows(ExpectedException::class.java) { support.requireArchivePermission(channel, 7L) }
        assertEquals(status, ex.statusCode)
    }

    @Test
    fun `DM과 음성 채널은 보관할 수 없다`() {
        assertStatus(HttpStatus.BAD_REQUEST, channel(type = ChannelType.DM, teamId = null, projectId = null))
        assertStatus(HttpStatus.BAD_REQUEST, channel(type = ChannelType.VOICE))
    }

    @Test
    fun `읽을 수 없는 채널은 보관 권한을 확인하기 전에 거부한다`() {
        every { evaluator.requireChannelAccess(10L, 7L, any()) } throws
            ExpectedException("채널 메시지 읽기 권한이 없습니다.", HttpStatus.FORBIDDEN)

        assertStatus(HttpStatus.FORBIDDEN, channel())
        verify(exactly = 0) { projectAccessRepository.findById(any()) }
    }

    @Test
    fun `프로젝트가 없는 채널은 팀 권한 archive_channels를 요구한다`() {
        every { evaluator.requireTeamPermission(10L, 7L, "archive_channels") } just runs

        assertDoesNotThrow { support.requireArchivePermission(channel(projectId = null), 7L) }

        verify { evaluator.requireTeamPermission(10L, 7L, "archive_channels") }
        verify(exactly = 0) { projectAccessRepository.findById(any()) }
    }

    @Test
    fun `프로젝트 채널은 해당 프로젝트의 채널 보관 권한을 요구한다`() {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member()
        every { projectAccessRepository.findById("5:7") } returns Optional.of(access(canChannelArchive = true))

        assertDoesNotThrow { support.requireArchivePermission(channel(), 7L) }
        verify(exactly = 0) { evaluator.requireTeamPermission(any(), any(), any()) }
    }

    @Test
    fun `프로젝트 채널 보관 권한이 없거나 권한 버전이 오래되면 거부한다`() {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member()

        every { projectAccessRepository.findById("5:7") } returns Optional.empty()
        assertStatus(HttpStatus.FORBIDDEN, channel())

        every { projectAccessRepository.findById("5:7") } returns Optional.of(access(canChannelArchive = false))
        assertStatus(HttpStatus.FORBIDDEN, channel())

        every { projectAccessRepository.findById("5:7") } returns
            Optional.of(
                access(canChannelArchive = true, sourceAuthorizationVersion = authorizationVersion.minusSeconds(1)),
            )
        assertStatus(HttpStatus.FORBIDDEN, channel())
    }

    @Test
    fun `팀 OWNER와 ADMIN은 프로젝트 권한 없이 보관할 수 있다`() {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member(role = "ADMIN")

        assertDoesNotThrow { support.requireArchivePermission(channel(), 7L) }
        verify(exactly = 0) { projectAccessRepository.findById(any()) }
    }
}
