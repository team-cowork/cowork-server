package com.cowork.gateway.controller

import com.cowork.gateway.config.ExternalHostProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@EnableConfigurationProperties(CoworkSwaggerUiProperties::class)
class SwaggerUiController(
    private val swaggerUiProperties: CoworkSwaggerUiProperties,
    private val externalHostProperties: ExternalHostProperties,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping(
        value = ["/swagger-ui.html", "/swagger-ui/index.html"],
        produces = [MediaType.TEXT_HTML_VALUE],
    )
    fun swaggerUi(): String {
        val urlsJson = objectMapper.writeValueAsString(swaggerUiProperties.urls + externalHostSwaggerUrl())
        val primaryNameJson = objectMapper.writeValueAsString(swaggerUiProperties.primaryName)

        return """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>cowork API Docs</title>
              <link rel="stylesheet" href="/webjars/swagger-ui/swagger-ui.css">
              <link rel="icon" href="/webjars/swagger-ui/favicon-32x32.png">
            </head>
            <body>
              <div id="swagger-ui"></div>
              <script src="/webjars/swagger-ui/swagger-ui-bundle.js"></script>
              <script src="/webjars/swagger-ui/swagger-ui-standalone-preset.js"></script>
              <script>
                window.onload = function() {
                  window.ui = SwaggerUIBundle({
                    urls: $urlsJson,
                    "urls.primaryName": $primaryNameJson,
                    dom_id: "#swagger-ui",
                    deepLinking: true,
                    presets: [
                      SwaggerUIBundle.presets.apis,
                      SwaggerUIStandalonePreset
                    ],
                    plugins: [
                      SwaggerUIBundle.plugins.DownloadUrl
                    ],
                    layout: "StandaloneLayout"
                  });
                };
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    // TODO: 임시 - 홈서버 등 외부 호스트의 OpenAPI 문서를 Swagger UI 탭에 포함. ExternalRouteConfig 제거 시 함께 삭제할 것
    private fun externalHostSwaggerUrl(): List<CoworkSwaggerUiProperties.SwaggerUrl> {
        if (externalHostProperties.url.isBlank()) return emptyList()

        return listOf(
            CoworkSwaggerUiProperties.SwaggerUrl(
                name = externalHostProperties.name,
                url = "/v3/api-docs/${externalHostProperties.name}",
            ),
        )
    }
}

@ConfigurationProperties(prefix = "cowork.swagger-ui")
data class CoworkSwaggerUiProperties(val urls: List<SwaggerUrl> = emptyList(), val primaryName: String = "user") {
    data class SwaggerUrl(val name: String = "", val url: String = "")
}
