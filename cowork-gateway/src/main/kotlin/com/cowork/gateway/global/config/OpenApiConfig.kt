package com.cowork.gateway.global.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(
        title = "cowork Gateway API",
        description = "서비스별 API 요청을 전달하고 서비스 상태를 조회합니다.",
        version = "20261113.0",
    ),
    servers = [Server(url = "/")],
)
class OpenApiConfig
