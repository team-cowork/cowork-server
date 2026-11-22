package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.event.ThreadParentCommand
import com.cowork.channel.domain.thread.event.ThreadParentContract
import com.cowork.channel.domain.thread.event.ThreadStatePublisher
import com.cowork.channel.domain.thread.presentation.data.request.CreateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse
import com.cowork.channel.domain.thread.repository.ThreadRepository
import com.cowork.channel.domain.thread.service.CreateThreadService
import com.cowork.channel.global.outbox.OutboxWriter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

/**
 * 스레드를 검증 대기(`PENDING`) 상태로 만들고, 시작 메시지 검증 command를 같은 트랜잭션의 outbox로 보낸다.
 * 메시지는 cowork-chat이 소유하므로 시작 메시지가 그 채널에 있는지는 chat의 결과를 받아 확정한다.
 */
@Service
class CreateThreadServiceImpl(
    private val threadRepository: ThreadRepository,
    private val channelPermissionSupport: ChannelPermissionSupport,
    private val channelMessageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
    private val channelAccessGuard: ChannelAccessGuard,
    private val threadStatePublisher: ThreadStatePublisher,
    private val outboxWriter: OutboxWriter,
) : CreateThreadService {

    @Transactional
    override fun createThread(userId: Long, channelId: Long, request: CreateThreadRequest): ThreadResponse {
        if (!OBJECT_ID.matches(request.parentMessageId)) {
            throw ExpectedException("시작 메시지 ID 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        }
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        channelPermissionSupport.requireChannelAccess(channel, userId)
        channelAccessGuard.requireNotArchived(channel)
        channelMessageReadPolicyEvaluator.requirePermission(teamId, userId, channel, "thread_reply")
        if (threadRepository.existsByParentMessageId(request.parentMessageId)) {
            throw ExpectedException("이미 스레드가 있는 메시지입니다.", HttpStatus.CONFLICT)
        }

        val thread = threadRepository.saveAndFlush(
            Thread(
                channelId = channelId,
                name = request.name,
                parentMessageId = request.parentMessageId,
                createdBy = userId,
            ),
        )
        threadStatePublisher.publish(thread)
        outboxWriter.enqueue(
            ThreadParentContract.COMMAND_TOPIC,
            thread.id.toString(),
            ThreadParentCommand(threadId = thread.id, channelId = channelId, parentMessageId = thread.parentMessageId),
        )
        return ThreadResponse.of(thread)
    }

    private companion object {
        val OBJECT_ID = Regex("^[0-9a-f]{24}$")
    }
}
