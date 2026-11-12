package com.cowork.channel.domain.meetingNote.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class MeetingNoteAttachment(
    @Column(name = "object_key", nullable = false, length = 200)
    val objectKey: String,
    @Column(nullable = false, length = 255)
    val name: String,
)
