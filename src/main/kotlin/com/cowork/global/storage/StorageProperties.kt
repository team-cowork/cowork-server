package com.cowork.global.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "cowork.storage")
data class StorageProperties(
    val endpoint: String?,
    val publicEndpoint: String?,
    val region: String = "ap-northeast-2",
    val bucket: String,
    val accessKey: String?,
    val secretKey: String?,
    val pathStyleAccess: Boolean = false,
    val presignedUrlTtl: Duration = Duration.ofMinutes(10),
)
