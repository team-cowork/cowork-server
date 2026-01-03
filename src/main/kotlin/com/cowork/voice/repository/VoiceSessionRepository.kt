package com.cowork.voice.repository

import com.cowork.voice.domain.VoiceSession
import com.cowork.voice.domain.VoiceSessionStatus
import org.springframework.data.jpa.repository.JpaRepository

interface VoiceSessionRepository : JpaRepository<VoiceSession, Long> {

    fun findFirstByChannelIdAndStatusOrderByIdDesc(channelId: Long, status: VoiceSessionStatus): VoiceSession?

    fun findByRoomName(roomName: String): VoiceSession?

    fun findAllByChannelIdAndStatus(channelId: Long, status: VoiceSessionStatus): List<VoiceSession>
}
