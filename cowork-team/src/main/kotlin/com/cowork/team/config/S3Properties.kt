package com.cowork.team.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "s3")
data class S3Properties(
    val bucket: String,
    val baseUrl: String,
    val presignedPutExpiryMinutes: Long = 10,
    val maxFileSizeBytes: Long = 1048576,
    val allowedContentTypes: List<String> = listOf("image/jpeg", "image/png", "image/webp"),
)
