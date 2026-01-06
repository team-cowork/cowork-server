package com.cowork.voice.service

import com.cowork.global.error.BusinessException
import com.cowork.voice.dto.LiveKitWebhookEvent
import com.cowork.voice.livekit.LiveKitWebhookVerifier
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class VoiceWebhookService(
    private val liveKitWebhookVerifier: LiveKitWebhookVerifier,
    private val voiceService: VoiceService,
    private val objectMapper: ObjectMapper,
) {

    fun handle(authorization: String?, body: String) {
        liveKitWebhookVerifier.verify(authorization, body)
        val event = try {
            objectMapper.readValue(body, LiveKitWebhookEvent::class.java)
        } catch (e: JacksonException) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "웹훅 본문을 해석할 수 없습니다.")
        }
        val roomName = event.room?.name ?: return
        when (event.event) {
            "participant_joined" -> event.participant?.let { voiceService.handleParticipantJoined(roomName, it.identity) }
            "participant_left" -> event.participant?.let { voiceService.handleParticipantLeft(roomName, it.identity) }
            "room_finished" -> voiceService.handleRoomFinished(roomName)
        }
    }
}
