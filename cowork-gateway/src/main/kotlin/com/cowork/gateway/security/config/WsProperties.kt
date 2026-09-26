package com.cowork.gateway.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ws")
data class WsProperties(val allowedOrigins: List<String> = emptyList())
