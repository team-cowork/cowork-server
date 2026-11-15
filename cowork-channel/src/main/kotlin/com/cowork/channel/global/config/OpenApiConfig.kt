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
    info = Info(
        title = "cowork Channel API",
        description = "팀·프로젝트 채널과 개인 대화, 회의록과 공유 계정을 관리합니다.",
        version = "20261113.0",
    ),
    servers = [Server(url = "/api/channel", description = "Gateway")],
    security = [SecurityRequirement(name = "BearerAuth")],
)
@SecurityScheme(
    name = "BearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "cowork 액세스 토큰",
)
class OpenApiConfig
