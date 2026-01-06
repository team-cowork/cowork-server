package com.cowork.voice.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.voice.dto.JoinVoiceResponse
import com.cowork.voice.dto.VoiceParticipantResponse
import com.cowork.voice.dto.VoiceSessionResponse
import com.cowork.voice.service.VoiceService
import com.cowork.voice.service.VoiceWebhookService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "음성")
@RestController
@RequestMapping("/voice")
class VoiceController(
    private val voiceService: VoiceService,
    private val voiceWebhookService: VoiceWebhookService,
) {

    @Operation(summary = "음성 채널 참여 (LiveKit 토큰 발급)")
    @PostMapping("/channels/{channelId}/join")
    fun join(@CurrentUser user: AuthUser, @PathVariable channelId: Long): JoinVoiceResponse =
        voiceService.join(channelId, user.id)

    @Operation(summary = "음성 채널 나가기")
    @PostMapping("/channels/{channelId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(@CurrentUser user: AuthUser, @PathVariable channelId: Long) = voiceService.leave(channelId, user.id)

    @Operation(summary = "음성 채널 참여자 조회")
    @GetMapping("/channels/{channelId}/participants")
    fun getParticipants(@CurrentUser user: AuthUser, @PathVariable channelId: Long): List<VoiceParticipantResponse> =
        voiceService.getParticipants(channelId, user.id)

    @Operation(summary = "음성 세션 조회")
    @GetMapping("/sessions/{sessionId}")
    fun getSession(@CurrentUser user: AuthUser, @PathVariable sessionId: Long): VoiceSessionResponse =
        voiceService.getSession(sessionId, user.id)

    @Operation(summary = "LiveKit 웹훅 수신", security = [])
    @PostMapping("/webhook", consumes = ["application/webhook+json", "application/json"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun webhook(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody body: String,
    ) = voiceWebhookService.handle(authorization, body)
}
