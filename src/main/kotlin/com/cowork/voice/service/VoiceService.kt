package com.cowork.voice.service

import com.cowork.channel.domain.ChannelType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import com.cowork.preference.service.PreferenceReader
import com.cowork.user.service.UserService
import com.cowork.voice.domain.VoiceParticipant
import com.cowork.voice.domain.VoiceSession
import com.cowork.voice.domain.VoiceSessionStatus
import com.cowork.voice.dto.JoinVoiceResponse
import com.cowork.voice.dto.VoiceParticipantResponse
import com.cowork.voice.dto.VoiceSessionResponse
import com.cowork.voice.dto.VoiceSocketEvent
import com.cowork.voice.livekit.LiveKitProperties
import com.cowork.voice.livekit.LiveKitTokenIssuer
import com.cowork.voice.repository.VoiceParticipantRepository
import com.cowork.voice.repository.VoiceSessionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class VoiceService(
    private val voiceSessionRepository: VoiceSessionRepository,
    private val voiceParticipantRepository: VoiceParticipantRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    private val preferenceReader: PreferenceReader,
    private val userService: UserService,
    private val liveKitTokenIssuer: LiveKitTokenIssuer,
    private val liveKitProperties: LiveKitProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
    fun join(channelId: Long, userId: Long): JoinVoiceResponse {
        requireVoiceChannel(channelId, userId)
        val now = LocalDateTime.now(clock)
        val session = voiceSessionRepository.findFirstByChannelIdAndStatusOrderByIdDesc(channelId, VoiceSessionStatus.ACTIVE)
            ?: voiceSessionRepository.save(
                VoiceSession(channelId = channelId, roomName = "channel-$channelId-${UUID.randomUUID()}", startedAt = now),
            )

        if (voiceParticipantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(session.id, userId) == null) {
            val maxParticipants = preferenceReader.findVoiceMaxParticipants(channelId) ?: DEFAULT_MAX_PARTICIPANTS
            if (voiceParticipantRepository.countBySessionIdAndLeftAtIsNull(session.id) >= maxParticipants) {
                throw BusinessException(HttpStatus.CONFLICT, "음성 채널 정원이 가득 찼습니다.")
            }
            voiceParticipantRepository.save(VoiceParticipant(sessionId = session.id, userId = userId, joinedAt = now))
            eventPublisher.publishEvent(VoiceSocketEvent("PARTICIPANT_JOINED", channelId, userId))
        }

        val user = userService.getSummaries(listOf(userId))[userId]
        val displayName = user?.nickname ?: user?.name ?: userId.toString()
        return JoinVoiceResponse(
            sessionId = session.id,
            roomName = session.roomName,
            serverUrl = liveKitProperties.url,
            token = liveKitTokenIssuer.issue(session.roomName, userId.toString(), displayName),
        )
    }

    private fun requireVoiceChannel(channelId: Long, userId: Long) {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        if (channel.type != ChannelType.VOICE) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "음성 채널이 아닙니다.")
        }
    }

    companion object {
        private const val DEFAULT_MAX_PARTICIPANTS = 25
    }
}
