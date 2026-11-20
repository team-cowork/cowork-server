package com.cowork.channel.domain.meetingNote.event

import com.cowork.channel.domain.meetingNote.repository.MeetingNoteAttachmentStateRepository
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
class MeetingNoteAttachmentSnapshotPublisher(
    private val meetingNoteAttachmentStateRepository: MeetingNoteAttachmentStateRepository,
    private val meetingNoteAttachmentPublisher: MeetingNoteAttachmentPublisher,
    private val projectionSnapshotCompletionPublisher: ProjectionSnapshotCompletionPublisher,
    private val projectionSnapshotLock: ProjectionSnapshotLock,
    private val projectionReadinessState: ProjectionReadinessState,
    private val projectionStreams: ProjectionStreams,
    platformTransactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(platformTransactionManager)

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    fun publishAll() {
        projectionSnapshotLock.tryRun("publishMeetingNoteAttachmentSnapshots") {
            if (!projectionReadinessState.isCaughtUp(projectionStreams.channelStateUpstream)) return@tryRun
            var afterId = 0L
            while (true) {
                afterId = transaction.execute {
                    val batch = meetingNoteAttachmentStateRepository.findSnapshotBatch(afterId, PageRequest.of(0, 500))
                    batch.forEach(meetingNoteAttachmentPublisher::enqueue)
                    batch.lastOrNull()?.noteId
                } ?: break
            }
            if (!projectionReadinessState.isCaughtUp(projectionStreams.channelStateUpstream)) return@tryRun
            projectionSnapshotCompletionPublisher.publishCompleted(setOf(MeetingNoteAttachmentPublisher.TOPIC))
        }
    }
}
