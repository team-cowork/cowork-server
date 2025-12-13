package com.cowork.notification.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.notification.dto.RegisterDeviceTokenRequest
import com.cowork.notification.service.DeviceTokenService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "알림")
@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val deviceTokenService: DeviceTokenService,
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
}
