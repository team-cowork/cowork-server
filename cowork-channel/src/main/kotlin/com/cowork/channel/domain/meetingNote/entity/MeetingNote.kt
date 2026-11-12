package com.cowork.channel.domain.meetingNote.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_meeting_notes")
class MeetingNote(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "template_id", nullable = false)
    val templateId: Long,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    var content: String,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @ElementCollection
    @CollectionTable(name = "tb_meeting_note_attachments", joinColumns = [JoinColumn(name = "note_id")])
    @OrderColumn(name = "position")
    var attachments: MutableList<MeetingNoteAttachment> = mutableListOf(),
) {
    fun update(title: String?, content: String?, attachments: List<MeetingNoteAttachment>? = null) {
        title?.let { this.title = it }
        content?.let { this.content = it }
        attachments?.let {
            this.attachments.clear()
            this.attachments.addAll(it)
            updatedAt = LocalDateTime.now()
        }
    }
}
