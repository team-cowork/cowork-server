package com.cowork.channel.meetingnote.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_meeting_note_templates")
class MeetingNoteTemplate(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = false,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    fun rename(name: String) {
        this.name = name
    }

    fun activate() {
        isActive = true
    }

    fun deactivate() {
        isActive = false
    }
}
