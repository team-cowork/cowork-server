package com.cowork.voice.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "tb_voice_sessions")
class VoiceSession(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "room_name", nullable = false, unique = true, length = 100)
    val roomName: String,

    @Column(name = "started_at", nullable = false)
    val startedAt: LocalDateTime,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: VoiceSessionStatus = VoiceSessionStatus.ACTIVE
        protected set

    @Column(name = "ended_at")
    var endedAt: LocalDateTime? = null
        protected set

    val isActive: Boolean get() = status == VoiceSessionStatus.ACTIVE

    fun end(now: LocalDateTime) {
        if (!isActive) return
        status = VoiceSessionStatus.ENDED
        endedAt = now
    }
}
