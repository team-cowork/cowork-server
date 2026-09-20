package com.cowork.gateway.swagger.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cowork.swagger-ui")
data class CoworkSwaggerUiProperties(val urls: List<SwaggerUrl> = emptyList(), val primaryName: String = "user")
