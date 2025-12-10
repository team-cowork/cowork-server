package com.cowork.notification.dto

import com.cowork.notification.domain.DevicePlatform

data class RegisterDeviceTokenRequest(
    val token: String,
    val platform: DevicePlatform,
)
