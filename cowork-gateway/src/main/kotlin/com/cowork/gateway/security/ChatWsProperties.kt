package com.cowork.gateway.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.chat-ws")
data class ChatWsProperties(val allowedOrigins: List<String> = emptyList())
