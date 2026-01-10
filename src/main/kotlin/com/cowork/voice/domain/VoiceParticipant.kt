package com.cowork.voice.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "tb_voice_participants")
class VoiceParticipant(
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "joined_at", nullable = false)
    val joinedAt: LocalDateTime,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    @Column(name = "left_at")
    var leftAt: LocalDateTime? = null
        protected set

    val isActive: Boolean get() = leftAt == null

    fun leave(now: LocalDateTime) {
        if (leftAt == null) leftAt = now
    }
}
