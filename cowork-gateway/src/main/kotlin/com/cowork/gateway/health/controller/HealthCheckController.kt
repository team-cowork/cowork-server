package com.cowork.gateway.health.controller

import com.cowork.gateway.response.model.CommonApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration

@Tag(name = "Health", description = "서비스 헬스 체크 API")
@RestController
@RequestMapping("/api/health")
class HealthCheckController(webClientBuilder: WebClient.Builder, private val routeLocator: RouteLocator) {
    private val webClient = webClientBuilder.build()

    @Operation(
        summary = "전체 서비스 상태 조회",
        description = """게이트웨이 라우트에 등록된 모든 서비스의 상태를 반환합니다.

- **UP**: 설정된 모든 upstream의 readiness가 정상
- **DEGRADED**: 일부 upstream의 readiness가 비정상
- **DOWN**: 모든 upstream에 연결할 수 없거나 readiness가 비정상""",
    )
    @ApiResponse(
        responseCode = "200",
        description = "서비스 상태 목록",
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = CommonApiResponse::class),
                examples = [
                    ExampleObject(
                        value = """
                {
                  "status": "OK",
                  "code": 200,
                  "message": "OK",
                  "data": {
                    "cowork-user": "UP",
                    "cowork-team": "UP",
                    "cowork-authorization": "DEGRADED",
                    "cowork-chat": "DOWN"
                  }
                }""",
                    ),
                ],
            ),
        ],
    )
    @GetMapping
    fun checkAll(): Mono<ResponseEntity<CommonApiResponse<Map<String, ServiceStatus>>>> = routeLocator.routes
        .filter { it.metadata["cowork-service"] is String && it.metadata["health-path"] is String }
        .map { route ->
            val service = route.metadata["cowork-service"] as String
            val path = route.metadata["health-path"] as String
            val scheme = if (route.uri.scheme ==
                "wss"
            ) {
                "https"
            } else if (route.uri.scheme == "ws") {
                "http"
            } else {
                route.uri.scheme
            }
            service to URI(scheme, route.uri.authority, path, null, null)
        }
        .distinct()
        .groupBy { it.first }
        .flatMap { endpoints ->
            endpoints.flatMap { (_, endpoint) ->
                webClient.get().uri(endpoint)
                    .exchangeToMono { response ->
                        response.releaseBody().thenReturn(response.statusCode().is2xxSuccessful)
                    }
                    .timeout(Duration.ofSeconds(2))
                    .onErrorReturn(false)
            }.collectList().map { results ->
                endpoints.key() to when {
                    results.all { it } -> ServiceStatus.UP
                    results.any { it } -> ServiceStatus.DEGRADED
                    else -> ServiceStatus.DOWN
                }
            }
        }
        .collectMap<String, ServiceStatus>({ it.first }, { it.second })
        .map { ResponseEntity.ok(CommonApiResponse.success(it)) }

    enum class ServiceStatus {
        UP,
        DEGRADED,
        DOWN,
    }
}
