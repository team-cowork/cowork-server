package com.cowork.global.storage

data class PresignedUploadResponse(
    val uploadUrl: String,
    val objectKey: String,
)
