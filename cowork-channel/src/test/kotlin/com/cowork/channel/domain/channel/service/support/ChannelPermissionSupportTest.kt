package com.cowork.channel.domain.channel.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.access.ChannelAccessProjection
import com.cowork.channel.domain.channelRolePolicy.access.ChannelAccessProjectionRepository
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
import java.time.Instant

class ChannelPermissionSupportTest {
    private val memberRepository = mockk<DmParticipantRepository>()
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val membershipRepository = mockk<TeamMembershipRepository>()
    private val accessRepository = mockk<ChannelAccessProjectionRepository>()
    private val evaluator = ChannelMessageReadPolicyEvaluator(
        membershipRepository,
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        accessRepository,
        mockk<ProjectionReadinessGate>(relaxed = true),
        mockk(),
    )
    private val support = ChannelPermissionSupport(memberRepository, teamPermissionService, evaluator)

    @Test
    fun `판정 결과가 허용하면 비공개 텍스트 채널에 멤버 등록 없이 접근한다`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        stubPolicy(messageRead = true, voiceConnect = false)
        support.requireChannelAccess(channel(isPrivate = true), 7L)
        verify(exactly = 0) { memberRepository.existsByChannelIdAndUserId(any(), any()) }
    }

    @Test
    fun `공개 채널도 팀 membership 다음 message_read 정책을 검사함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        stubPolicy(messageRead = true, voiceConnect = false)

        support.requireChannelAccess(channel(isPrivate = false), 7L)

        verify { accessRepository.findAllById(listOf("3:7")) }
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
            TeamMembership(
                teamId = 10L,
                userId = 7L,
                role = "MEMBER",
                sourceOccurredAt = occurredAt,
                authorizationVersion = occurredAt,
            )
        every { accessRepository.findAllById(listOf("3:7")) } returns listOf(
            ChannelAccessProjection(
                accessKey = "3:7",
                channelId = 3L,
                userId = 7L,
                teamId = 10L,
                allowedPermissions = buildSet {
                    if (messageRead) add("message_read")
                    if (voiceConnect == true) add("voice_connect")
                },
                // 비공개 채널 테스트도 같은 판정 결과를 쓰므로, 비공개 접근은 접근 권한과 같게 둔다.
                canReadPrivate = messageRead || voiceConnect == true,
                sourceOccurredAt = occurredAt,
                sourceMembershipVersion = occurredAt,
                sourceAuthorizationVersion = occurredAt,
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
