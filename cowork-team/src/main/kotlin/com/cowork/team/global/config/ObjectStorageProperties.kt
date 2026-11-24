package com.cowork.team.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.NamedInterface

@ConfigurationProperties(prefix = "object-storage")
@NamedInterface("config", propagate = false)
data class ObjectStorageProperties(
    val bucket: String,
    val publicApiBaseUrl: String,
    val presignedPutExpiryMinutes: Long = 10,
    val maxFileSizeBytes: Long = 1048576,
    val allowedContentTypes: List<String> = listOf("image/jpeg", "image/png", "image/webp"),
) {
    val publicBaseUrl: String get() = "${publicApiBaseUrl.trimEnd('/')}/api/storage/$bucket"
}
