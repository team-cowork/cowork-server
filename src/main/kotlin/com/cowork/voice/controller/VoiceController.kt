package com.cowork.voice.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.voice.dto.JoinVoiceResponse
import com.cowork.voice.dto.VoiceParticipantResponse
import com.cowork.voice.dto.VoiceSessionResponse
import com.cowork.voice.service.VoiceService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/voice")
class VoiceController(
    private val voiceService: VoiceService,
) {

    @PostMapping("/channels/{channelId}/join")
    fun join(@CurrentUser user: AuthUser, @PathVariable channelId: Long): JoinVoiceResponse =
        voiceService.join(channelId, user.id)

    @PostMapping("/channels/{channelId}/leave")
    fun leave(@CurrentUser user: AuthUser, @PathVariable channelId: Long) = voiceService.leave(channelId, user.id)

    @GetMapping("/channels/{channelId}/participants")
    fun getParticipants(@CurrentUser user: AuthUser, @PathVariable channelId: Long): List<VoiceParticipantResponse> =
        voiceService.getParticipants(channelId, user.id)
}
