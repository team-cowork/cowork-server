package com.cowork.user.dto

data class PresignedUrlResponse(
    val uploadUrl: String,
    val objectKey: String,
)
