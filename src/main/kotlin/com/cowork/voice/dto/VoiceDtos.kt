package com.cowork.voice.dto

import com.cowork.user.dto.UserSummaryResponse
import com.cowork.voice.domain.VoiceParticipant
import com.cowork.voice.domain.VoiceSession
import com.cowork.voice.domain.VoiceSessionStatus
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDateTime

data class JoinVoiceResponse(
    val sessionId: Long,
    val roomName: String,
    val serverUrl: String,
    val token: String,
)

data class VoiceParticipantResponse(
    val userId: Long,
    val joinedAt: LocalDateTime,
    val user: UserSummaryResponse?,
) {
    companion object {
        fun of(participant: VoiceParticipant, user: UserSummaryResponse?) = VoiceParticipantResponse(
            userId = participant.userId,
            joinedAt = participant.joinedAt,
            user = user,
        )
    }
}

data class VoiceSessionResponse(
    val id: Long,
    val channelId: Long,
    val status: VoiceSessionStatus,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val participants: List<VoiceParticipantResponse>,
) {
    companion object {
        fun of(session: VoiceSession, participants: List<VoiceParticipantResponse>) = VoiceSessionResponse(
            id = session.id,
            channelId = session.channelId,
            status = session.status,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            participants = participants,
        )
    }
}

data class VoiceSocketEvent(
    val type: String,
    val channelId: Long,
    val userId: Long?,
)
