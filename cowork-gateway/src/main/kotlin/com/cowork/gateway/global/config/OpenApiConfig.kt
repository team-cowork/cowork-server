package com.cowork.gateway.global.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(title = "cowork Gateway API", version = "20261111.2"),
    servers = [Server(url = "/")],
)
class OpenApiConfig
