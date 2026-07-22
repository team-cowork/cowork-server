package com.cowork.gateway.security

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(@field:NotBlank val secret: String)
