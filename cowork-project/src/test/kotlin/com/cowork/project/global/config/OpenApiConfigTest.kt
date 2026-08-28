package com.cowork.project.global.config

import com.cowork.project.domain.project.presentation.controller.ProjectController
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiConfigTest {

    @Test
    fun `통합 Swagger는 project canonical server와 Bearer JWT를 사용한다`() {
        val definition = OpenApiConfig::class.java.getAnnotation(OpenAPIDefinition::class.java)
        val securityScheme = OpenApiConfig::class.java.getAnnotation(SecurityScheme::class.java)

        assertEquals("/api/project", definition.servers.single().url)
        assertEquals("BearerAuth", definition.security.single().name)
        assertEquals("BearerAuth", securityScheme.name)
        assertEquals("bearer", securityScheme.scheme)
    }

    @Test
    fun `내부 project operation은 Swagger에서 숨긴다`() {
        val hiddenMethods = setOf("getMyMembership", "getTeamId")

        ProjectController::class.java.declaredMethods
            .filter { it.name in hiddenMethods }
            .forEach { method -> assertTrue(method.getAnnotation(Operation::class.java).hidden) }
    }
}
