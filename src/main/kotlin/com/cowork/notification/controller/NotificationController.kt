package com.cowork.notification.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.notification.dto.RegisterDeviceTokenRequest
import com.cowork.notification.service.DeviceTokenService
import com.cowork.notification.service.SseEmitterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Tag(name = "알림")
@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val deviceTokenService: DeviceTokenService,
    private val sseEmitterRegistry: SseEmitterRegistry,
) {

    @Operation(summary = "FCM 기기 토큰 등록")
    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun registerToken(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: RegisterDeviceTokenRequest,
    ) = deviceTokenService.register(user.id, request.token, request.platform)

    @Operation(summary = "FCM 기기 토큰 삭제")
    @DeleteMapping("/tokens/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteToken(@CurrentUser user: AuthUser, @PathVariable token: String) =
        deviceTokenService.delete(user.id, token)

    @Operation(summary = "실시간 알림 스트림 구독 (SSE)")
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@CurrentUser user: AuthUser): SseEmitter = sseEmitterRegistry.subscribe(user.id)
}
