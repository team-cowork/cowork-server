package com.cowork.channel.domain.channelRolePolicy.service.support

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class ChannelRolePolicyAccessSupport(
    private val roleRepository: TeamRoleProjectionRepository,
    private val policyRepository: ChannelRolePolicyProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
) {
    fun requireRole(teamId: Long, roleId: Long) {
        projectionReadinessGate.requireReady()
        val role = roleRepository.findById(roleId).orElse(null)
        if (role == null || role.teamId != teamId || role.deleted) {
            throw ExpectedException("팀 역할을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    fun requirePolicy(teamId: Long, channelId: Long, roleId: Long) {
        projectionReadinessGate.requireReady()
        val key = ChannelRolePolicyProjection.key(teamId, channelId, roleId)
        val policy = policyRepository.findById(key).orElse(null)
        if (policy == null || policy.deleted) {
            throw ExpectedException("채널 역할 정책을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }
}
