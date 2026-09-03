package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandOperationRepository
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationMapper
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationProjectionFinalizer
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.service.QueryChannelRolePolicyOperationService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class QueryChannelRolePolicyOperationServiceImpl(
    private val operationRepository: ChannelRolePolicyCommandOperationRepository,
    private val operationMapper: ChannelRolePolicyOperationMapper,
    private val finalizer: ChannelRolePolicyOperationProjectionFinalizer,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : QueryChannelRolePolicyOperationService {
    @Transactional
    override fun execute(
        actorId: Long,
        channelId: Long,
        roleId: Long,
        operationId: String,
    ): ChannelRolePolicyOperationResponse {
        val operation = operationRepository.findByIdForUpdate(operationId)
            ?.takeIf {
                it.actorId == actorId &&
                    it.channelId == channelId &&
                    it.roleId == roleId
            }
            ?: throw ExpectedException("채널 역할 정책 작업을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        if (teamId != operation.teamId) {
            throw ExpectedException("채널 역할 정책 작업을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        teamPermissionService.requireTeamMember(teamId, actorId)
        channelPermissionSupport.requireChannelManager(channel, actorId)
        finalizer.tryFinalize(operation)
        return operationMapper.toResponse(operation)
    }
}
