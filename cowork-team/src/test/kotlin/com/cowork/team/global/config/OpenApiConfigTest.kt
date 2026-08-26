package com.cowork.team.global.config

import com.cowork.team.domain.teamMember.presentation.controller.TeamMemberController
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiConfigTest {

    @Test
    fun `통합 Swagger는 team canonical server와 Bearer JWT를 사용한다`() {
        val definition = OpenApiConfig::class.java.getAnnotation(OpenAPIDefinition::class.java)
        val securityScheme = OpenApiConfig::class.java.getAnnotation(SecurityScheme::class.java)

        assertEquals("/api/team", definition.servers.single().url)
        assertEquals("BearerAuth", definition.security.single().name)
        assertEquals("BearerAuth", securityScheme.name)
        assertEquals("bearer", securityScheme.scheme)
    }

    @Test
    fun `내부 멤버 확인 operation은 Swagger에서 숨긴다`() {
        val operation = TeamMemberController::class.java
            .getDeclaredMethod("isMember", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .getAnnotation(Operation::class.java)

        assertTrue(operation.hidden)
    }
}
