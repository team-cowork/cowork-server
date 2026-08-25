package com.cowork.gateway.controller

import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// TODO(temporary health dashboard): Remove this class with the temporary /health dashboard.
@RestController
@RequestMapping("/health")
class HealthDashboardController {

    // TODO(temporary health dashboard): Remove this handler with the temporary /health dashboard.
    @GetMapping(produces = [MediaType.TEXT_HTML_VALUE])
    fun healthDashboard(): ResponseEntity<Resource> = resourceResponse(INDEX_HTML, MediaType.TEXT_HTML)

    // TODO(temporary health dashboard): Remove this handler with the temporary /health dashboard.
    @GetMapping("/health.css", produces = ["text/css"])
    fun healthStyles(): ResponseEntity<Resource> = resourceResponse(HEALTH_CSS, CSS_MEDIA_TYPE)

    // TODO(temporary health dashboard): Remove this handler with the temporary /health dashboard.
    @GetMapping("/health.js", produces = ["text/javascript"])
    fun healthScript(): ResponseEntity<Resource> = resourceResponse(HEALTH_JAVASCRIPT, JAVASCRIPT_MEDIA_TYPE)

    // TODO(temporary health dashboard): Remove this method with the temporary /health dashboard.
    private fun resourceResponse(resource: Resource, mediaType: MediaType): ResponseEntity<Resource> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .contentType(mediaType)
        .body(resource)

    private companion object {
        private val INDEX_HTML = ClassPathResource("health-dashboard/index.html")
        private val HEALTH_CSS = ClassPathResource("health-dashboard/health.css")
        private val HEALTH_JAVASCRIPT = ClassPathResource("health-dashboard/health.js")
        private val CSS_MEDIA_TYPE = MediaType.parseMediaType("text/css")
        private val JAVASCRIPT_MEDIA_TYPE = MediaType.parseMediaType("text/javascript")
    }
}
