package com.cowork.voice.repository

import com.cowork.voice.domain.VoiceParticipant
import org.springframework.data.jpa.repository.JpaRepository

interface VoiceParticipantRepository : JpaRepository<VoiceParticipant, Long> {

    fun findAllBySessionIdAndLeftAtIsNull(sessionId: Long): List<VoiceParticipant>

    fun findFirstBySessionIdAndUserIdAndLeftAtIsNull(sessionId: Long, userId: Long): VoiceParticipant?

    fun countBySessionIdAndLeftAtIsNull(sessionId: Long): Long
}
