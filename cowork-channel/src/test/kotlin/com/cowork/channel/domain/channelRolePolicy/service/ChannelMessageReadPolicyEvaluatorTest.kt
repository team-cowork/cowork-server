package com.cowork.channel.domain.channelRolePolicy.service

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
import java.time.Instant
import java.util.Optional

class ChannelMessageReadPolicyEvaluatorTest {
    private val membershipRepository = mockk<TeamMembershipRepository>()
    private val assignmentRepository = mockk<TeamRoleAssignmentProjectionRepository>()
    private val tombstoneRepository = mockk<TeamRoleMemberTombstoneRepository>()
    private val roleRepository = mockk<TeamRoleProjectionRepository>()
    private val policyRepository = mockk<ChannelRolePolicyProjectionRepository>()
    private val readinessGate = mockk<ProjectionReadinessGate>(relaxed = true)
    private val evaluator = ChannelMessageReadPolicyEvaluator(
        membershipRepository,
        assignmentRepository,
        tombstoneRepository,
        roleRepository,
        policyRepository,
        readinessGate,
    )
    private val occurredAt = Instant.parse("2026-08-30T01:00:00Z")

    @Test
    fun `OWNER는 정책과 무관하게 모든 후보 채널을 읽음`() {
        every { membershipRepository.findByTeamIdAndUserId(10L, 7L) } returns membership("OWNER")

        assertEquals(setOf(1L, 2L), evaluator.readableChannelIds(10L, 7L, setOf(1L, 2L)))

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

        assertEquals(emptySet<Long>(), evaluator.readableChannelIds(10L, 7L, setOf(1L)))
    }

    @Test
    fun `명시 정책이 있는 가장 높은 priority만 평가하고 낮은 deny는 무시함`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 100), role(2L, 10)),
            policies = listOf(policy(1L, 1L, true), policy(1L, 2L, false)),
        )

        assertEquals(setOf(1L), evaluator.readableChannelIds(10L, 7L, setOf(1L)))
    }

    @Test
    fun `높은 priority role에 미지정이면 낮은 priority의 명시 정책을 상속함`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 100), role(2L, 10)),
            policies = listOf(policy(1L, 2L, true)),
        )

        assertEquals(setOf(1L), evaluator.readableChannelIds(10L, 7L, setOf(1L)))
    }

    @Test
    fun `같은 priority에서 true와 false가 충돌하면 false가 우선함`() {
        stubMemberWithRoles(
            roles = listOf(role(1L, 50), role(2L, 50)),
            policies = listOf(policy(1L, 1L, true), policy(1L, 2L, false)),
        )

        assertEquals(emptySet<Long>(), evaluator.readableChannelIds(10L, 7L, setOf(1L)))
    }

    private fun stubMemberWithRoles(roles: List<TeamRoleProjection>, policies: List<ChannelRolePolicyProjection>) {
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
                setOf(1L),
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

    private fun policy(channelId: Long, roleId: Long, messageRead: Boolean) = ChannelRolePolicyProjection(
        policyKey = "policy:10:$channelId:$roleId",
        teamId = 10L,
        channelId = channelId,
        roleId = roleId,
        messageRead = messageRead,
        sourceOccurredAt = occurredAt,
    )
}
