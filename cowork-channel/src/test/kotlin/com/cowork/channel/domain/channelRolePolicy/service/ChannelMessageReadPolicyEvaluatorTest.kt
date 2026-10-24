package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstone
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class ChannelMessageReadPolicyEvaluatorTest {
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
    private val occurredAt = Instant.parse("2026-10-25T00:00:00Z")

    @BeforeEach
    fun setup() {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member()
        every { tombstoneRepository.findById("10:7") } returns Optional.empty()
        every { tombstoneRepository.findAllById(any()) } returns emptyList()
        every { assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(10L, 7L) } returns emptyList()
        every { roleRepository.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(10L) } returns emptyList()
        every { roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(10L, any()) } returns emptyList()
        every {
            policyRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(10L, any(), any())
        } returns emptyList()
    }

    @Test
    fun `기본 역할은 할당 없이 모든 활성 멤버에게 적용된다`() {
        roles(role(1L, "message_read" to true, "voice_connect" to true, default = true))
        val channels = listOf(channel(), channel(2L, ChannelType.VOICE))
        assertEquals(channels, evaluator.filterReadable(10L, 7L, channels))
    }

    @Test
    fun `기본 권한 합집합에는 역할 priority와 false가 영향을 주지 않는다`() {
        roles(role(1L, "message_read" to false), role(2L, "message_read" to true))
        assertEquals(listOf(1L), evaluator.filterReadable(10L, 7L, listOf(channel())).map { it.id })
    }

    @Test
    fun `기본 역할 overwrite 다음 개별 역할 deny와 allow를 적용한다`() {
        roles(
            role(1L, "message_read" to true, default = true),
            role(2L),
            role(3L),
        )
        policies(
            policy(1L, "message_read" to false),
            policy(2L, "message_read" to false),
            policy(
                3L,
                "message_read" to true,
            ),
        )
        assertEquals(listOf(1L), evaluator.filterReadable(10L, 7L, listOf(channel())).map { it.id })
        policies(policy(1L, "message_read" to true), policy(2L, "message_read" to false))
        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel())))
    }

    @Test
    fun `비공개 채널에는 기본 권한 대신 명시 overwrite가 필요하다`() {
        roles(role(1L, "message_read" to true, default = true))
        val privateChannel = channel().apply { isPrivate = true }
        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(privateChannel)))
        policies(policy(1L, "message_read" to true))
        assertEquals(listOf(privateChannel), evaluator.filterReadable(10L, 7L, listOf(privateChannel)))
    }

    @Test
    fun `음성 채널 가시성은 voice_connect이고 텍스트는 message_read이다`() {
        roles(role(1L, "voice_connect" to true, "message_read" to false, default = true))
        val voice = channel(2L, ChannelType.VOICE)
        assertEquals(listOf(voice), evaluator.filterReadable(10L, 7L, listOf(channel(), voice)))
    }

    @Test
    fun `관리와 재정렬 권한이 있어도 가시성이 없으면 거부한다`() {
        roles(role(1L, "channel_manage" to true, "channel_reorder" to true))
        assertThrows(ExpectedException::class.java) {
            evaluator.requirePermission(10L, 7L, channel(), "channel_manage")
        }
        assertThrows(ExpectedException::class.java) { evaluator.requireReorder(10L, 7L, listOf(channel())) }
    }

    @Test
    fun `생성한 채널도 관리 및 재정렬 권한을 요구한다`() {
        roles(role(1L, "message_read" to true))
        val owned = channel(createdBy = 7L)
        assertThrows(ExpectedException::class.java) { evaluator.requirePermission(10L, 7L, owned, "channel_manage") }
        assertThrows(ExpectedException::class.java) { evaluator.requireReorder(10L, 7L, listOf(owned)) }
        roles(role(1L, "message_read" to true, "channel_manage" to true, "channel_reorder" to true))
        evaluator.requirePermission(10L, 7L, owned, "channel_manage")
        evaluator.requireReorder(10L, 7L, listOf(owned))
    }

    @Test
    fun `활성 OWNER와 ADMIN만 정책을 우회한다`() {
        for (role in listOf("OWNER", "ADMIN")) {
            every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member(role)
            assertEquals(listOf(1L), evaluator.filterReadable(10L, 7L, listOf(channel())).map { it.id })
            every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns member(role, active = false)
            assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel())))
        }
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns null
        assertEquals(emptyList<Channel>(), evaluator.filterReadable(10L, 7L, listOf(channel())))
    }

    @Test
    fun `재가입하면 기본 역할을 얻지만 탈퇴 전 개별 역할은 복구하지 않는다`() {
        roles(role(1L, "message_read" to true, default = true), role(2L, "channel_manage" to true))
        every { tombstoneRepository.findById("10:7") } returns
            Optional.of(TeamRoleMemberTombstone("10:7", 10L, 7L, occurredAt.plusSeconds(1)))
        assertEquals(listOf(1L), evaluator.filterReadable(10L, 7L, listOf(channel())).map { it.id })
        assertThrows(ExpectedException::class.java) {
            evaluator.requirePermission(10L, 7L, channel(), "channel_manage")
        }
        every { assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(10L, 7L) } returns
            listOf(assignment(2L, occurredAt.plusSeconds(2)))
        evaluator.requirePermission(10L, 7L, channel(), "channel_manage")
    }

    @Test
    fun `수신자 일괄 조회도 기본 역할과 활성 membership을 사용한다`() {
        roles(role(1L, "message_read" to true, default = true))
        every { assignmentRepository.findAllByTeamIdAndAccountIdInAndDeletedFalse(10L, setOf(7L)) } returns emptyList()
        val members =
            listOf(member(), member("ADMIN", active = false), TeamMembership(teamId = 99, userId = 8, role = "OWNER"))
        assertEquals(setOf(7L), evaluator.readableUserIds(channel(), members))
    }

    @Test
    fun `채널 생성 권한은 기본 역할과 개별 역할의 합집합으로 확인한다`() {
        roles(role(1L, "manage_channels" to true, default = true))
        evaluator.requireTeamPermission(10L, 7L, "manage_channels")
        roles(role(1L, "manage_channels" to false, default = true))
        assertThrows(ExpectedException::class.java) { evaluator.requireTeamPermission(10L, 7L, "manage_channels") }
    }

    @Test
    fun `채널 overwrite는 manage_roles와 역할 서열 및 보유 권한을 검사한다`() {
        roles(role(1L, "message_read" to true, "manage_roles" to true))
        val target = role(2L)
        every { roleRepository.findById(2L) } returns Optional.of(target)
        evaluator.requireRolePolicyManagement(10L, 7L, channel(), 2L, mapOf("message_read" to true))
        assertThrows(ExpectedException::class.java) {
            evaluator.requireRolePolicyManagement(10L, 7L, channel(), 2L, mapOf("message_manage" to true))
        }
        target.priority = 100
        assertThrows(ExpectedException::class.java) {
            evaluator.requireRolePolicyManagement(10L, 7L, channel(), 2L, mapOf("message_read" to true))
        }
    }

    @Test
    fun `거부 overwrite만 추가할 때 변경하지 않는 기본 허용 권한은 요구하지 않는다`() {
        roles(role(1L, "message_read" to true, "manage_roles" to true))
        every { roleRepository.findById(2L) } returns Optional.of(role(2L, "message_manage" to true))

        evaluator.requireRolePolicyManagement(10L, 7L, channel(), 2L, mapOf("message_send" to false))
    }

    @Test
    fun `기존 거부를 유지하면 보유하지 않은 권한도 거부 상태로 저장할 수 있다`() {
        roles(role(1L, "message_read" to true, "manage_roles" to true))
        every { roleRepository.findById(2L) } returns Optional.of(role(2L, "message_manage" to true))
        policies(policy(2L, "message_manage" to false))

        evaluator.requireRolePolicyManagement(
            10L,
            7L,
            channel(),
            2L,
            mapOf("message_send" to false, "message_manage" to false),
        )
    }

    @Test
    fun `기존 거부를 누락하거나 삭제하여 다른 역할의 권한을 복원할 수 없다`() {
        roles(role(1L, "message_read" to true, "manage_roles" to true))
        every { roleRepository.findById(2L) } returns Optional.of(role(2L))
        policies(policy(2L, "message_manage" to false))

        assertThrows(ExpectedException::class.java) {
            evaluator.requireRolePolicyManagement(10L, 7L, channel(), 2L, mapOf("message_send" to false))
        }
        assertThrows(ExpectedException::class.java) {
            evaluator.requireRolePolicyManagement(10L, 7L, channel(), 2L, null)
        }
    }

    @Test
    fun `overwrite 삭제로 보유하지 않은 기본 권한을 복원할 수 없다`() {
        roles(role(1L, "message_read" to true, "manage_roles" to true))
        every { roleRepository.findById(2L) } returns Optional.of(role(2L, "message_manage" to true))
        assertThrows(ExpectedException::class.java) {
            evaluator.requireRolePolicyManagement(10L, 7L, channel(), 2L, null)
        }
    }

    private fun roles(vararg roles: TeamRoleProjection) {
        every { roleRepository.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(10L) } returns
            roles.filter { it.isDefault }
        every { roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(10L, any()) } answers {
            val ids = secondArg<Set<Long>>()
            roles.filter { it.roleId in ids }
        }
        every { assignmentRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(10L, 7L) } returns
            roles.filterNot { it.isDefault }.map { assignment(it.roleId) }
    }

    private fun policies(vararg policies: ChannelRolePolicyProjection) {
        every {
            policyRepository.findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(10L, any(), any())
        } returns policies.toList()
    }

    private fun member(role: String = "MEMBER", active: Boolean = true) =
        TeamMembership(teamId = 10L, userId = 7L, role = role, active = active, sourceOccurredAt = occurredAt)

    private fun role(roleId: Long, vararg permissions: Pair<String, Boolean>, default: Boolean = false) =
        TeamRoleProjection(
            roleId = roleId,
            teamId = 10L,
            priority = (100 - roleId).toInt(),
            isDefault = default,
            permissionsJson = objectMapper.writeValueAsString(permissions.toMap()),
            sourceOccurredAt = occurredAt,
        )

    private fun assignment(roleId: Long, version: Instant = occurredAt) =
        TeamRoleAssignmentProjection("10:7:$roleId", 10L, 7L, roleId, sourceOccurredAt = version)

    private fun channel(id: Long = 1L, type: ChannelType = ChannelType.TEXT, createdBy: Long = 1L) = Channel(
        id = id,
        teamId = 10L,
        name = "channel",
        type = type,
        viewType = if (type == ChannelType.VOICE) ChannelViewType.VOICE else ChannelViewType.TEXT,
        description = null,
        createdBy = createdBy,
    )

    private fun policy(roleId: Long, vararg permissions: Pair<String, Boolean>) = ChannelRolePolicyProjection(
        policyKey = "policy:10:1:$roleId",
        teamId = 10L,
        channelId = 1L,
        roleId = roleId,
        permissionsJson = objectMapper.writeValueAsString(permissions.toMap()),
        sourceOccurredAt = occurredAt,
    )
}
