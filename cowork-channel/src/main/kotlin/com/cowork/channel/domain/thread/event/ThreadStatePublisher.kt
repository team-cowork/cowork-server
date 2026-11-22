package com.cowork.channel.domain.thread.event

import com.cowork.channel.domain.channel.entity.nextChannelStateOccurredAt
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.entity.ThreadState
import com.cowork.channel.domain.thread.repository.ThreadStateRepository
import com.cowork.channel.global.outbox.OutboxWriter
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 스레드 상태를 상태 원장에 남기고 같은 트랜잭션의 outbox로 `channel.thread.state.v1`에 발행한다.
 * cowork-chat은 이 상태로 스레드 메시지 전송을 검증하고 채널 구성원에게 알린다.
 */
@Component
class ThreadStatePublisher(
    private val threadStateRepository: ThreadStateRepository,
    private val outboxWriter: OutboxWriter,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(thread: Thread, deleted: Boolean = false) {
        val previous = threadStateRepository.findForUpdate(thread.id)
        val version = nextChannelStateOccurredAt(previous?.stateOccurredAt, Instant.now())
        val state = previous ?: ThreadState(
            threadId = thread.id,
            channelId = thread.channelId,
            name = thread.name,
            parentMessageId = thread.parentMessageId,
            createdBy = thread.createdBy,
            status = thread.status,
            stateOccurredAt = version,
        )
        state.name = thread.name
        state.status = thread.status
        state.archived = thread.isArchived
        state.deleted = deleted
        state.stateOccurredAt = version
        threadStateRepository.save(state)
        enqueue(state)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteChannels(channelIds: Collection<Long>) {
        if (channelIds.isEmpty()) return
        threadStateRepository.findActiveByChannelIdsForUpdate(channelIds).forEach {
            it.deleted = true
            it.stateOccurredAt = nextChannelStateOccurredAt(it.stateOccurredAt, Instant.now())
            enqueue(it)
        }
    }

    fun enqueue(state: ThreadState) {
        outboxWriter.enqueue(
            TOPIC,
            state.threadId.toString(),
            mapOf(
                "threadId" to state.threadId,
                "channelId" to state.channelId,
                "name" to state.name,
                "parentMessageId" to state.parentMessageId,
                "createdBy" to state.createdBy,
                "status" to state.status.name,
                "archived" to state.archived,
                "deleted" to state.deleted,
                "occurredAt" to state.stateOccurredAt,
            ),
        )
    }

    companion object {
        const val TOPIC = "channel.thread.state.v1"
    }
}
