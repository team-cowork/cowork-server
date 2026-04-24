package com.cowork.user.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = "minio")
data class MinioProperties(
    val bucket: String,
    @DefaultValue("5") val presignedPutExpiryMinutes: Long,
    @DefaultValue("15") val presignedGetExpiryMinutes: Long,
    @DefaultValue("5242880") val maxFileSizeBytes: Long,
    val allowedContentTypes: List<String> = listOf("image/jpeg", "image/png", "image/webp"),
)

