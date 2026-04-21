package com.cowork.gateway.controller

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SwaggerUiController {

    @GetMapping(
        value = ["/swagger-ui.html", "/swagger-ui/index.html"],
        produces = [MediaType.TEXT_HTML_VALUE]
    )
    fun swaggerUi(): String {
        return """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Cowork API Docs</title>
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
                    urls: [
                      { name: "authorization", url: "/v3/api-docs/authorization" },
                      { name: "user", url: "/v3/api-docs/user" },
                      { name: "team", url: "/v3/api-docs/team" },
                      { name: "voice", url: "/v3/api-docs/voice" },
                      { name: "chat", url: "/v3/api-docs/chat" }
                    ],
                    "urls.primaryName": "user",
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
}
