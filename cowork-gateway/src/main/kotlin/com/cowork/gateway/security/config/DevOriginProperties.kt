package com.cowork.gateway.security.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.dev-origin")
data class DevOriginProperties(@field:NotBlank val credential: String)
