package com.cowork.preference.controller

import com.cowork.global.error.BusinessException
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.dto.ChannelNotificationRequest
import com.cowork.preference.dto.ChannelNotificationResponse
import com.cowork.preference.dto.SettingsResponse
import com.cowork.preference.service.PreferenceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "설정")
@RestController
@RequestMapping("/preferences")
class PreferenceController(
    private val preferenceService: PreferenceService,
) {

    @Operation(summary = "설정 조회 (account, team, project, text-channel, voice-channel)")
    @GetMapping("/{resourceType}/{resourceId}")
    fun getSettings(
        @CurrentUser user: AuthUser,
        @PathVariable resourceType: String,
        @PathVariable resourceId: Long,
    ): SettingsResponse = preferenceService.getSettings(parse(resourceType), resourceId, user.id)

    @Operation(summary = "설정 변경 (전달한 키만 반영, null은 삭제)")
    @PutMapping("/{resourceType}/{resourceId}")
    fun updateSettings(
        @CurrentUser user: AuthUser,
        @PathVariable resourceType: String,
        @PathVariable resourceId: Long,
        @RequestBody settings: Map<String, Any?>,
    ): SettingsResponse = preferenceService.updateSettings(parse(resourceType), resourceId, user.id, settings)

    @Operation(summary = "채널 알림 설정 조회")
    @GetMapping("/account/me/channels/{channelId}/notification")
    fun getChannelNotification(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
    ): ChannelNotificationResponse = preferenceService.getChannelNotification(user.id, channelId)

    @Operation(summary = "채널 알림 설정 변경")
    @PutMapping("/account/me/channels/{channelId}/notification")
    fun updateChannelNotification(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: ChannelNotificationRequest,
    ): ChannelNotificationResponse = preferenceService.updateChannelNotification(user.id, channelId, request.notification)

    private fun parse(resourceType: String): ResourceType =
        ResourceType.fromPath(resourceType)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "지원하지 않는 설정 유형입니다.")
}
