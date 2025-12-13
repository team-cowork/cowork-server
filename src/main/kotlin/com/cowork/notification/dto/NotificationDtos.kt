package com.cowork.notification.dto

import com.cowork.notification.domain.DevicePlatform
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class RegisterDeviceTokenRequest(
    @field:NotBlank
    @field:Size(max = 512)
    val token: String,
    @field:NotNull
    val platform: DevicePlatform,
)
