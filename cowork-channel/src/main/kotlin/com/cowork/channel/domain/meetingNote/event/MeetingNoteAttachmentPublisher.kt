package com.cowork.channel.domain.meetingNote.event

import com.cowork.channel.domain.channel.entity.nextChannelStateOccurredAt
import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteAttachmentState
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteAttachmentStateRepository
import com.cowork.channel.global.outbox.OutboxWriter
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class MeetingNoteAttachmentPublisher(
    private val meetingNoteAttachmentStateRepository: MeetingNoteAttachmentStateRepository,
    private val outboxWriter: OutboxWriter,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(note: MeetingNote, deleted: Boolean = false) {
        val previous = meetingNoteAttachmentStateRepository.findForUpdate(note.id)
        val version = nextChannelStateOccurredAt(previous?.stateOccurredAt, Instant.now())
        val state =
            previous
                ?: MeetingNoteAttachmentState(note.id, note.channelId, note.createdBy, "[]", stateOccurredAt = version)
        state.attachmentsJson = if (deleted) "[]" else objectMapper.writeValueAsString(note.attachments)
        state.deleted = deleted
        state.stateOccurredAt = version
        meetingNoteAttachmentStateRepository.save(state)
        enqueue(state)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteChannels(channelIds: Collection<Long>) {
        meetingNoteAttachmentStateRepository.findActiveByChannelIdsForUpdate(channelIds).forEach {
            it.deleted = true
            it.attachmentsJson = "[]"
            it.stateOccurredAt = nextChannelStateOccurredAt(it.stateOccurredAt, Instant.now())
            enqueue(it)
        }
    }

    fun enqueue(state: MeetingNoteAttachmentState) {
        outboxWriter.enqueue(
            TOPIC,
            state.noteId.toString(),
            mapOf(
                "noteId" to state.noteId,
                "channelId" to state.channelId,
                "authorId" to state.authorId,
                "attachments" to objectMapper.readTree(state.attachmentsJson),
                "deleted" to state.deleted,
                "occurredAt" to state.stateOccurredAt,
            ),
        )
    }

    companion object {
        const val TOPIC = "channel.meeting-note-attachments.state.v1"
    }
}
