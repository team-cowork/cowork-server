package com.cowork.team.global.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(title = "cowork Team API", description = "팀 관리 서비스 API", version = "20261025.0"),
    servers = [Server(url = "/api/team", description = "Gateway")],
    security = [SecurityRequirement(name = "BearerAuth")],
)
@SecurityScheme(
    name = "BearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "Gateway에서 검증할 Cowork JWT",
)
class OpenApiConfig
