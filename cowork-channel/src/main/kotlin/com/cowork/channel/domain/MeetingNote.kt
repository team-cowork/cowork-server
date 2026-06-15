package com.cowork.channel.domain

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

    @Column(columnDefinition = "JSON", nullable = false)
    var content: String,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(title: String?, content: String?) {
        title?.let { this.title = it }
        content?.let { this.content = it }
    }
}
