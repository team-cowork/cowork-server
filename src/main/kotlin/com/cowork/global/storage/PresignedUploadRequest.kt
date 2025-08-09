package com.cowork.global.storage

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

data class PresignedUploadRequest(
    @field:NotBlank
    val contentType: String,
    @field:Positive
    val fileSize: Long,
)
