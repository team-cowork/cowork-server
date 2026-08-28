package com.cowork.channel.global.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenApiConfigTest {

    @Test
    fun `통합 Swagger는 channel canonical server와 Bearer JWT를 사용한다`() {
        val definition = OpenApiConfig::class.java.getAnnotation(OpenAPIDefinition::class.java)
        val securityScheme = OpenApiConfig::class.java.getAnnotation(SecurityScheme::class.java)

        assertEquals("/api/channel", definition.servers.single().url)
        assertEquals("BearerAuth", definition.security.single().name)
        assertEquals("BearerAuth", securityScheme.name)
        assertEquals("bearer", securityScheme.scheme)
    }
}
