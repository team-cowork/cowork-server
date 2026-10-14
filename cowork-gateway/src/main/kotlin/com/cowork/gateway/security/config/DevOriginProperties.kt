package com.cowork.gateway.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.dev-origin")
data class DevOriginProperties(val enforce: Boolean = false, val credential: String = "") {
    init {
        require(!enforce || credential.isNotBlank()) {
            "app.dev-origin.credential is required when app.dev-origin.enforce is true"
        }
    }
}
