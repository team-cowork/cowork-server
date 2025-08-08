package com.cowork.auth.client

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cowork.datagsm")
data class DataGsmProperties(
    val clientId: String,
    val tokenUrl: String,
    val userInfoUrl: String,
)
