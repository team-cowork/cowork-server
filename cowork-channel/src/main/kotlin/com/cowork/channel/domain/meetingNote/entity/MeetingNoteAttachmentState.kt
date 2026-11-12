package com.cowork.channel.domain.meetingNote.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_meeting_note_attachment_states")
class MeetingNoteAttachmentState(
    @Id
    @Column(name = "note_id")
    val noteId: Long,
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,
    @Column(name = "author_id", nullable = false)
    val authorId: Long,
    @Column(name = "attachments_json", nullable = false, columnDefinition = "JSON")
    var attachmentsJson: String,
    @Column(nullable = false)
    var deleted: Boolean = false,
    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
)
