package com.cowork.channel.global.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(title = "cowork Channel API", description = "채널 관리 서비스 API", version = "20261025.0"),
    servers = [Server(url = "/api/channel", description = "Gateway")],
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
