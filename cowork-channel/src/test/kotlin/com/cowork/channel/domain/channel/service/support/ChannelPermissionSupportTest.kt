package com.cowork.channel.domain.channel.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class ChannelPermissionSupportTest {
    private val memberRepository = mockk<DmParticipantRepository>()
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val membershipRepository = mockk<TeamMembershipRepository>()
    private val assignmentRepository = mockk<TeamRoleAssignmentProjectionRepository>()
    private val tombstoneRepository = mockk<TeamRoleMemberTombstoneRepository>()
    private val roleRepository = mockk<TeamRoleProjectionRepository>()
    private val policyRepository = mockk<ChannelRolePolicyProjectionRepository>()
    private val objectMapper = jacksonObjectMapper()
    private val evaluator = ChannelMessageReadPolicyEvaluator(
        membershipRepository,
        assignmentRepository,
        tombstoneRepository,
        roleRepository,
        policyRepository,
        mockk<ProjectionReadinessGate>(relaxed = true),
        objectMapper,
    )
    private val support = ChannelPermissionSupport(memberRepository, teamPermissionService, evaluator)

    @Test
    fun `비공개 채널도 OWNER는 별도 멤버 등록 없이 접근한다`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns
            TeamMembership(teamId = 10L, userId = 7L, role = "OWNER")
        support.requireChannelAccess(channel(isPrivate = true), 7L)
        verify(exactly = 0) { memberRepository.existsByChannelIdAndUserId(any(), any()) }
    }

    @Test
    fun `공개 채널도 팀 membership 다음 message_read 정책을 검사함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        stubPolicy(messageRead = true, voiceConnect = false)

        support.requireChannelAccess(channel(isPrivate = false), 7L)

        verify { policyRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(10L, setOf(3L), setOf(1L)) }
    }

    @Test
    fun `공개 음성 채널은 message_read 거부여도 voice_connect 허용이면 접근 가능함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        stubPolicy(messageRead = false, voiceConnect = true)

        support.requireChannelAccess(channel(isPrivate = false, type = ChannelType.VOICE), 7L)
    }

    @Test
    fun `음성 채널은 message_read 허용이어도 voice_connect 거부 또는 미지정이면 접근 불가함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        for (voiceConnect in listOf(false, null)) {
            stubPolicy(messageRead = true, voiceConnect = voiceConnect)

            val exception = assertThrows(ExpectedException::class.java) {
                support.requireChannelAccess(channel(isPrivate = false, type = ChannelType.VOICE), 7L)
            }

            assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
            assertEquals("음성 채널 연결 권한이 없습니다.", exception.message)
        }
    }

    @Test
    fun `비공개 음성 채널은 채널 멤버이고 voice_connect가 허용되면 접근 가능함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        every { memberRepository.existsByChannelIdAndUserId(3L, 7L) } returns true
        stubPolicy(messageRead = false, voiceConnect = true)

        support.requireChannelAccess(channel(isPrivate = true, type = ChannelType.VOICE), 7L)
    }

    @Test
    fun `비공개 음성 채널은 voice_connect로 접근하며 별도 멤버 등록을 요구하지 않는다`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        stubPolicy(messageRead = false, voiceConnect = true)
        support.requireChannelAccess(channel(isPrivate = true, type = ChannelType.VOICE), 7L)
        verify(exactly = 0) { memberRepository.existsByChannelIdAndUserId(any(), any()) }
    }

    @Test
    fun `텍스트 채널은 voice_connect 허용이어도 message_read 거부이면 접근 불가함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        stubPolicy(messageRead = false, voiceConnect = true)

        val exception = assertThrows(ExpectedException::class.java) {
            support.requireChannelAccess(channel(isPrivate = false), 7L)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
        assertEquals("채널 메시지 읽기 권한이 없습니다.", exception.message)
    }

    private fun stubPolicy(messageRead: Boolean, voiceConnect: Boolean?) {
        val occurredAt = Instant.parse("2026-10-23T00:00:00Z")
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns
            TeamMembership(teamId = 10L, userId = 7L, role = "MEMBER")
        every { tombstoneRepository.findById("10:7") } returns Optional.empty()
        every { assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(10L, 7L) } returns listOf(
            TeamRoleAssignmentProjection("10:7:1", 10L, 7L, 1L, sourceOccurredAt = occurredAt),
        )
        every { roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(10L, setOf(1L)) } returns listOf(
            TeamRoleProjection(roleId = 1L, teamId = 10L, priority = 10, sourceOccurredAt = occurredAt),
        )
        every {
            policyRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(10L, setOf(3L), setOf(1L))
        } returns listOf(
            ChannelRolePolicyProjection(
                policyKey = "policy:10:3:1",
                teamId = 10L,
                channelId = 3L,
                roleId = 1L,
                messageRead = messageRead,
                permissionsJson = objectMapper.writeValueAsString(
                    buildMap {
                        put("message_read", messageRead)
                        voiceConnect?.let { put("voice_connect", it) }
                    },
                ),
                sourceOccurredAt = occurredAt,
            ),
        )
    }

    private fun channel(isPrivate: Boolean, type: ChannelType = ChannelType.TEXT) = Channel(
        id = 3L,
        teamId = 10L,
        name = "general",
        type = type,
        viewType = if (type == ChannelType.VOICE) ChannelViewType.VOICE else ChannelViewType.TEXT,
        description = null,
        isPrivate = isPrivate,
        createdBy = 1L,
    )
}
