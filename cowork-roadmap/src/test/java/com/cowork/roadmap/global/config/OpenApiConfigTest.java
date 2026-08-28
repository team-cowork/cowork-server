package com.cowork.roadmap.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

class OpenApiConfigTest {

    @Test
    void integratedSwaggerUsesCanonicalServerAndBearerJwt() {
        OpenAPIDefinition definition = OpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
        SecurityScheme securityScheme = OpenApiConfig.class.getAnnotation(SecurityScheme.class);

        assertEquals("/api/roadmap", definition.servers()[0].url());
        assertEquals("BearerAuth", definition.security()[0].name());
        assertEquals("BearerAuth", securityScheme.name());
        assertEquals("bearer", securityScheme.scheme());
    }
}
