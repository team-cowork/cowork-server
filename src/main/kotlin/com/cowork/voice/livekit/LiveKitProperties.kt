package com.cowork.voice.livekit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "cowork.livekit")
data class LiveKitProperties(
    val url: String,
    val apiKey: String,
    val apiSecret: String,
    val tokenTtl: Duration = Duration.ofHours(6),
)
