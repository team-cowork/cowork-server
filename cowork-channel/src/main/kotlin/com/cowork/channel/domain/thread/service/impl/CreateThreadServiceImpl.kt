package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.presentation.data.request.CreateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse
import com.cowork.channel.domain.thread.repository.ThreadRepository
import com.cowork.channel.domain.thread.service.CreateThreadService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateThreadServiceImpl(
    private val threadRepository: ThreadRepository,
    private val permissions: ChannelPermissionSupport,
    private val evaluator: ChannelMessageReadPolicyEvaluator,
    private val channelAccessGuard: ChannelAccessGuard,
) : CreateThreadService {

    @Transactional
    override fun createThread(userId: Long, channelId: Long, request: CreateThreadRequest): ThreadResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        permissions.requireChannelAccess(channel, userId)
        evaluator.requirePermission(teamId, userId, channel, "thread_reply")

        val thread = threadRepository.save(
            Thread(
                channelId = channelId,
                name = request.name,
                parentMessageId = request.parentMessageId,
                createdBy = userId,
            ),
        )
        return ThreadResponse.of(thread)
    }
}
