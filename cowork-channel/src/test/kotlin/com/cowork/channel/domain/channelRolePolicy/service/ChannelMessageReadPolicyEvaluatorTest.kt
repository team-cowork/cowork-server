package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class ChannelMessageReadPolicyEvaluatorTest {
    private val membershipRepository = mockk<TeamMembershipRepository>()
    private val assignmentRepository = mockk<TeamRoleAssignmentProjectionRepository>()
    private val tombstoneRepository = mockk<TeamRoleMemberTombstoneRepository>()
    private val roleRepository = mockk<TeamRoleProjectionRepository>()
    private val policyRepository = mockk<ChannelRolePolicyProjectionRepository>()
    private val readinessGate = mockk<ProjectionReadinessGate>(relaxed = true)
    private val objectMapper = jacksonObjectMapper()
    private val evaluator = ChannelMessageReadPolicyEvaluator(
        membershipRepository,
        assignmentRepository,
        tombstoneRepository,
        roleRepository,
        policyRepository,
        readinessGate,
        objectMapper,
    )
    private val occurredAt = Instant.parse("2026-08-30T01:00:00Z")

    @Test
    fun `OWNER는 정책과 무관하게 모든 후보 채널을 읽음`() {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns membership("OWNER")

        val channels = listOf(channel(1L, ChannelType.TEXT), channel(2L, ChannelType.VOICE))
        assertEquals(channels, evaluator.filterReadable(10L, 7L, channels))

        verify(exactly = 0) { assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(any(), any()) }
        verify(exactly = 0) {
            policyRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(any(), any(), any())
        }
    }

    @Test
    fun `ADMIN도 custom role의 명시 정책이 없으면 기본 거부됨`() {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns membership("ADMIN")
        every { tombstoneRepository.findById("10:7") } returns Optional.empty()
        every { assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(10L, 7L) } returns emptyList()

        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel(1L, ChannelType.TEXT))))
    }

    @Test
    fun `명시 정책이 있는 가장 높은 priority만 평가하고 낮은 deny는 무시함`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 100), role(2L, 10)),
            policies = listOf(policy(1L, 1L, true), policy(1L, 2L, false)),
        )

        val channel = channel(1L, ChannelType.TEXT)
        assertEquals(listOf(channel), evaluator.filterReadable(10L, 7L, listOf(channel)))
    }

    @Test
    fun `높은 priority 역할의 기본 거부가 낮은 역할의 허용보다 우선함`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 100), role(2L, 10)),
            policies = listOf(policy(1L, 2L, true)),
        )

        val channel = channel(1L, ChannelType.TEXT)
        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel)))
    }

    @Test
    fun `같은 priority에서 true와 false가 충돌하면 false가 우선함`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 50), role(2L, 50)),
            policies = listOf(policy(1L, 1L, true), policy(1L, 2L, false)),
        )

        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel(1L, ChannelType.TEXT))))
    }

    @Test
    fun `목록은 음성 채널의 voice_connect와 텍스트 채널의 message_read로 필터링하고 순서를 유지함`() {
        val channels = listOf(
            channel(2L, ChannelType.TEXT),
            channel(3L, ChannelType.VOICE),
            channel(1L, ChannelType.VOICE),
            channel(4L, ChannelType.TEXT),
        )
        stubMemberWithRoles(
            roles = listOf(role(1L, 10)),
            policies = listOf(
                policy(1L, 1L, messageRead = false, voiceConnect = true),
                policy(2L, 1L, messageRead = true, voiceConnect = false),
                policy(3L, 1L, messageRead = true, voiceConnect = false),
                policy(4L, 1L, messageRead = false, voiceConnect = true),
            ),
            channelIds = setOf(1L, 2L, 3L, 4L),
        )

        assertEquals(listOf(2L, 1L), evaluator.filterReadable(10L, 7L, channels).map { it.id })
    }

    @Test
    fun `음성 채널은 voice_connect가 미지정이면 message_read 허용 여부와 무관하게 숨김`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 10)),
            policies = listOf(policy(1L, 1L, messageRead = true)),
        )

        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel(1L, ChannelType.VOICE))))
    }

    @Test
    fun `음성 채널은 message_read 없이 voice_connect만 허용되어도 노출됨`() {
        val channel = channel(1L, ChannelType.VOICE)
        stubMemberWithRoles(
            roles = listOf(role(1L, 10)),
            policies = listOf(policy(1L, 1L, voiceConnect = true)),
        )

        assertEquals(listOf(channel), evaluator.filterReadable(10L, 7L, listOf(channel)))
    }

    @Test
    fun `음성 채널은 가장 높은 역할의 기본 voice_connect 권한을 상속함`() {
        val channel = channel(1L, ChannelType.VOICE)
        stubMemberWithRoles(
            roles = listOf(
                role(1L, 100).apply {
                    permissionsJson = "{\"voice_connect\":true}"
                },
                role(2L, 50),
                role(3L, 10),
            ),
            policies = listOf(
                policy(1L, 1L, messageRead = false),
                policy(1L, 2L, voiceConnect = true),
                policy(1L, 3L, voiceConnect = false),
            ),
        )

        assertEquals(listOf(channel), evaluator.filterReadable(10L, 7L, listOf(channel)))
    }

    @Test
    fun `음성 채널도 같은 priority의 voice_connect가 충돌하면 거부가 우선함`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 50), role(2L, 50)),
            policies = listOf(
                policy(1L, 1L, messageRead = true, voiceConnect = true),
                policy(1L, 2L, messageRead = true, voiceConnect = false),
            ),
        )

        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel(1L, ChannelType.VOICE))))
    }

    @Test
    fun `OWNER는 voice_connect 정책이 없어도 음성 채널 후보를 볼 수 있음`() {
        val channel = channel(1L, ChannelType.VOICE)
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns membership("OWNER")

        assertEquals(listOf(channel), evaluator.filterReadable(10L, 7L, listOf(channel)))
    }

    @Test
    fun `역할의 기본 읽기 권한은 채널별 정책 없이 적용된다`() {
        val role = role(1L, 10).apply { permissionsJson = "{\"message_read\":true,\"voice_connect\":true}" }
        stubMemberWithRoles(listOf(role), emptyList(), setOf(1L, 2L))
        val channels = listOf(channel(1L, ChannelType.TEXT), channel(2L, ChannelType.VOICE))
        assertEquals(channels, evaluator.filterReadable(10L, 7L, channels))
    }

    @Test
    fun `채널 거부는 같은 역할의 기본 읽기 허용을 덮어쓴다`() {
        val role = role(1L, 10).apply { permissionsJson = "{\"message_read\":true}" }
        stubMemberWithRoles(listOf(role), listOf(policy(1L, 1L, messageRead = false)))
        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel(1L, ChannelType.TEXT))))
    }

    @Test
    fun `비공개 채널만 기본 읽기 허용에서 제외하고 채널별 허용은 적용한다`() {
        val role = role(1L, 10).apply { permissionsJson = """{"message_read":true,"voice_connect":true}""" }
        val channels = listOf(
            channel(1L, ChannelType.TEXT),
            channel(2L, ChannelType.TEXT).apply { isPrivate = true },
            channel(3L, ChannelType.VOICE).apply { isPrivate = true },
            channel(4L, ChannelType.VOICE).apply { isPrivate = true },
            channel(5L, ChannelType.TEXT).apply { isPrivate = true },
        )
        stubMemberWithRoles(
            listOf(role),
            listOf(policy(4L, 1L, voiceConnect = true), policy(5L, 1L, messageRead = true)),
            channels.mapTo(mutableSetOf()) { it.id },
        )

        assertEquals(listOf(1L, 4L, 5L), evaluator.filterReadable(10L, 7L, channels).map { it.id })
    }

    @Test
    fun `비공개 채널도 높은 역할의 거부와 동순위 거부를 따른다`() {
        val channel = channel(1L, ChannelType.TEXT).apply { isPrivate = true }
        listOf(10, 20).forEach { priority ->
            stubMemberWithRoles(
                listOf(role(1L, priority), role(2L, 10)),
                listOf(policy(1L, 1L, messageRead = false), policy(1L, 2L, messageRead = true)),
            )
            assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel)))
        }
    }

    private fun stubMemberWithRoles(
        roles: List<TeamRoleProjection>,
        policies: List<ChannelRolePolicyProjection>,
        channelIds: Set<Long> = setOf(1L),
    ) {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns membership("MEMBER")
        every { tombstoneRepository.findById("10:7") } returns Optional.empty()
        every { assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(10L, 7L) } returns
            roles.map { assignment(it.roleId, occurredAt) }
        every {
            roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(10L, roles.mapTo(mutableSetOf()) { it.roleId })
        } returns roles
        every {
            policyRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(
                10L,
                channelIds,
                roles.mapTo(mutableSetOf()) { it.roleId },
            )
        } returns policies
    }

    private fun membership(role: String) = TeamMembership(
        teamId = 10L,
        userId = 7L,
        role = role,
        sourceOccurredAt = occurredAt,
    )

    private fun role(roleId: Long, priority: Int) = TeamRoleProjection(
        roleId = roleId,
        teamId = 10L,
        priority = priority,
        sourceOccurredAt = occurredAt,
    )

    private fun assignment(roleId: Long, version: Instant) = TeamRoleAssignmentProjection(
        projectionKey = "10:7:$roleId",
        teamId = 10L,
        accountId = 7L,
        roleId = roleId,
        sourceOccurredAt = version,
    )

    private fun channel(channelId: Long, type: ChannelType) = Channel(
        id = channelId,
        teamId = 10L,
        name = "channel",
        type = type,
        viewType = if (type == ChannelType.VOICE) ChannelViewType.VOICE else ChannelViewType.TEXT,
        description = null,
        createdBy = 1L,
    )

    private fun policy(channelId: Long, roleId: Long, messageRead: Boolean? = null, voiceConnect: Boolean? = null) =
        ChannelRolePolicyProjection(
            policyKey = "policy:10:$channelId:$roleId",
            teamId = 10L,
            channelId = channelId,
            roleId = roleId,
            permissionsJson = objectMapper.writeValueAsString(
                buildMap {
                    messageRead?.let { put("message_read", it) }
                    voiceConnect?.let { put("voice_connect", it) }
                },
            ),
            messageRead = messageRead,
            sourceOccurredAt = occurredAt,
        )
}
