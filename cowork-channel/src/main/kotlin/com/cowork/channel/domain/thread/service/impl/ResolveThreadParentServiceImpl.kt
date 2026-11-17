package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.thread.entity.ThreadStatus
import com.cowork.channel.domain.thread.event.ThreadParentResult
import com.cowork.channel.domain.thread.event.ThreadStatePublisher
import com.cowork.channel.domain.thread.repository.ThreadRepository
import com.cowork.channel.domain.thread.service.ResolveThreadParentService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * cowork-chat의 시작 메시지 검증 결과로 검증 대기 중인 스레드를 확정하거나 지운다.
 *
 * 거부된 스레드는 지워서 시작 메시지의 unique 제약을 풀고, 상태 원장에는 `REJECTED` 삭제 상태로 남긴다.
 * 이미 처리했거나 채널 삭제로 사라진 스레드의 결과는 무시하므로 재전달에도 안전하다.
 */
@Service
class ResolveThreadParentServiceImpl(
    private val threadRepository: ThreadRepository,
    private val threadStatePublisher: ThreadStatePublisher,
) : ResolveThreadParentService {

    @Transactional
    override fun resolve(result: ThreadParentResult) {
        val thread = threadRepository.findByIdForUpdate(result.threadId) ?: return
        require(thread.channelId == result.channelId && thread.parentMessageId == result.parentMessageId) {
            "thread parent result does not match the thread"
        }
        if (thread.status != ThreadStatus.PENDING) return

        thread.resolve(result.accepted)
        if (result.accepted) {
            threadStatePublisher.publish(thread)
        } else {
            threadStatePublisher.publish(thread, deleted = true)
            threadRepository.delete(thread)
        }
    }
}
