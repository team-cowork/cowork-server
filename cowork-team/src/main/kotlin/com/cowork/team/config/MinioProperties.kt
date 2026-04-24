package com.cowork.team.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "minio")
data class MinioProperties(
    val bucket: String,
    val publicBaseUrl: String,
    val presignedPutExpiryMinutes: Long = 10,
    val maxFileSizeBytes: Long = 1048576,
    val allowedContentTypes: List<String> = listOf("image/jpeg", "image/png", "image/webp"),
)

