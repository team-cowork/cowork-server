package com.cowork.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * TODO: 홈서버 등 Eureka에 등록되지 않은 외부 호스트로의 임시 연동 설정.
 * EXTERNAL_HOST_URL 환경변수가 비어 있으면 관련 라우트/헬스체크/Swagger 연동이 전부 비활성화된다.
 * 정식 서비스로 편입되거나 더 이상 필요하지 않으면 이 클래스와 [ExternalRouteConfig],
 * HealthCheckController·SwaggerUiController의 연동 지점을 함께 제거할 것.
 */
@Deprecated("임시 외부 호스트 연동 설정 - 정리 대상")
@ConfigurationProperties(prefix = "external.host")
data class ExternalHostProperties(
    val url: String = "",
    val name: String = "external",
    val healthPath: String = "/health",
    val docsPath: String = "/v3/api-docs",
)
