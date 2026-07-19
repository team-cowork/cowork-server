package com.cowork.gateway.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.cloud.gateway.route.builder.filters
import org.springframework.cloud.gateway.route.builder.routes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * TODO: 홈서버 등 Eureka 미등록 외부 호스트로 향하는 임시 라우트.
 * EXTERNAL_HOST_URL 환경변수가 설정된 경우에만 라우트가 추가되며,
 * 정식 서비스로 편입되거나 더 이상 필요하지 않으면 이 클래스를 제거할 것.
 */
@Deprecated("임시 외부 호스트 라우팅 - 정리 대상")
@Configuration
@EnableConfigurationProperties(ExternalHostProperties::class)
class ExternalRouteConfig(private val externalHostProperties: ExternalHostProperties) {

    @Bean
    fun externalRouteLocator(builder: RouteLocatorBuilder): RouteLocator = builder.routes {
        val url = externalHostProperties.url
        if (url.isNotBlank()) {
            val docsSegment = externalHostProperties.name

            route(id = "external-temp-route") {
                path("/api/external/**")
                filters { stripPrefix(2) }
                uri(url)
            }

            route(id = "external-temp-docs-route") {
                path("/v3/api-docs/$docsSegment")
                filters { rewritePath("/v3/api-docs/$docsSegment", externalHostProperties.docsPath) }
                uri(url)
            }
        }
    }
}
