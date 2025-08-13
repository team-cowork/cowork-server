package com.cowork.user.dto

import jakarta.validation.constraints.NotBlank

data class ConfirmProfileImageRequest(
    @field:NotBlank
    val objectKey: String,
)
