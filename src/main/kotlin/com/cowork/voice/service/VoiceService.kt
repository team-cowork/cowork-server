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

    @Transactional
    fun leave(channelId: Long, userId: Long) {
        channelAccessChecker.findChannel(channelId)
        val session = voiceSessionRepository.findFirstByChannelIdAndStatusOrderByIdDesc(channelId, VoiceSessionStatus.ACTIVE)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "진행 중인 음성 세션이 없습니다.")
        val participant = voiceParticipantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(session.id, userId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "음성 세션에 참여하고 있지 않습니다.")
        markLeft(session, participant)
    }

    @Transactional(readOnly = true)
    fun getParticipants(channelId: Long, userId: Long): List<VoiceParticipantResponse> {
        requireVoiceChannel(channelId, userId)
        val session = voiceSessionRepository.findFirstByChannelIdAndStatusOrderByIdDesc(channelId, VoiceSessionStatus.ACTIVE)
            ?: return emptyList()
        return toParticipantResponses(voiceParticipantRepository.findAllBySessionIdAndLeftAtIsNull(session.id))
    }

    @Transactional(readOnly = true)
    fun getSession(sessionId: Long, userId: Long): VoiceSessionResponse {
        val session = voiceSessionRepository.findByIdOrNull(sessionId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "음성 세션을 찾을 수 없습니다.")
        channelAccessChecker.requireReadable(session.channelId, userId)
        val participants = voiceParticipantRepository.findAllBySessionIdAndLeftAtIsNull(session.id)
        return VoiceSessionResponse.of(session, toParticipantResponses(participants))
    }

    @Transactional
    fun handleParticipantLeft(roomName: String, identity: String) {
        val session = voiceSessionRepository.findByRoomName(roomName) ?: return
        val userId = identity.toLongOrNull() ?: return
        val participant = voiceParticipantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(session.id, userId) ?: return
        markLeft(session, participant)
    }

    @Transactional
    fun handleParticipantJoined(roomName: String, identity: String) {
        val session = voiceSessionRepository.findByRoomName(roomName)?.takeIf { it.isActive } ?: return
        val userId = identity.toLongOrNull() ?: return
        if (voiceParticipantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(session.id, userId) != null) return
        voiceParticipantRepository.save(VoiceParticipant(sessionId = session.id, userId = userId, joinedAt = LocalDateTime.now(clock)))
        eventPublisher.publishEvent(VoiceSocketEvent("PARTICIPANT_JOINED", session.channelId, userId))
    }

    @Transactional
    fun handleRoomFinished(roomName: String) {
        val session = voiceSessionRepository.findByRoomName(roomName) ?: return
        endSession(session)
    }
    private fun markLeft(session: VoiceSession, participant: VoiceParticipant) {
        val now = LocalDateTime.now(clock)
        participant.leave(now)
        eventPublisher.publishEvent(VoiceSocketEvent("PARTICIPANT_LEFT", session.channelId, participant.userId))
        if (voiceParticipantRepository.findAllBySessionIdAndLeftAtIsNull(session.id).none { it.id != participant.id }) {
            session.end(now)
            eventPublisher.publishEvent(VoiceSocketEvent("SESSION_ENDED", session.channelId, null))
        }
    }

    private fun endSession(session: VoiceSession) {
        if (!session.isActive) return
        val now = LocalDateTime.now(clock)
        voiceParticipantRepository.findAllBySessionIdAndLeftAtIsNull(session.id).forEach { it.leave(now) }
        session.end(now)
        eventPublisher.publishEvent(VoiceSocketEvent("SESSION_ENDED", session.channelId, null))
    }

    private fun requireVoiceChannel(channelId: Long, userId: Long) {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        if (channel.type != ChannelType.VOICE) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "음성 채널이 아닙니다.")
        }
    }

    private fun toParticipantResponses(participants: List<VoiceParticipant>): List<VoiceParticipantResponse> {
        val users = userService.getSummaries(participants.map { it.userId })
        return participants.map { VoiceParticipantResponse.of(it, users[it.userId]) }
    }

    companion object {
        private const val DEFAULT_MAX_PARTICIPANTS = 25
    }
}
