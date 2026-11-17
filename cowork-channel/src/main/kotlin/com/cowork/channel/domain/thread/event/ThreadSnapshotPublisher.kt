package com.cowork.channel.domain.thread.event

import com.cowork.channel.domain.thread.repository.ThreadStateRepository
import com.cowork.channel.global.projection.ProjectionReadinessState
import com.cowork.channel.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.channel.global.projection.ProjectionSnapshotLock
import com.cowork.channel.global.projection.ProjectionStreams
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class ThreadSnapshotPublisher(
    private val states: ThreadStateRepository,
    private val publisher: ThreadStatePublisher,
    private val completion: ProjectionSnapshotCompletionPublisher,
    private val lock: ProjectionSnapshotLock,
    private val readinessState: ProjectionReadinessState,
    private val streams: ProjectionStreams,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    fun publishAll() {
        lock.tryRun("publishThreadStateSnapshots") {
            // 팀 삭제가 채널과 스레드를 지우므로, 그 upstream을 따라잡은 뒤에만 전체 상태를 낸다.
            if (!readinessState.isCaughtUp(streams.channelStateUpstream)) return@tryRun
            var afterId = 0L
            while (true) {
                afterId = transaction.execute {
                    val batch = states.findSnapshotBatch(afterId, PageRequest.of(0, 500))
                    batch.forEach(publisher::enqueue)
                    batch.lastOrNull()?.threadId
                } ?: break
            }
            if (!readinessState.isCaughtUp(streams.channelStateUpstream)) return@tryRun
            completion.publishCompleted(setOf(ThreadStatePublisher.TOPIC))
        }
    }
}
