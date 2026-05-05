package com.cowork.team.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "preference")
data class PreferenceProperties(
    val baseUrl: String,
)
