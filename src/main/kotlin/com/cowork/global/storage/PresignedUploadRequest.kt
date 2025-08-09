package com.cowork.global.storage


data class PresignedUploadRequest(
    val contentType: String,
    val fileSize: Long,
)
