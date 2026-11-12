package com.cowork.gateway.health.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
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

@Tag(name = "서비스 상태")
@RestController
@RequestMapping("/api/health")
class HealthCheckController(webClientBuilder: WebClient.Builder, private val routeLocator: RouteLocator) {
    private val webClient = webClientBuilder.build()

    @Operation(summary = "서비스 상태 조회")
    @ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                schemaProperties = [
                    SchemaProperty(name = "status", schema = Schema(type = "string", allowableValues = ["200 OK"])),
                    SchemaProperty(name = "code", schema = Schema(type = "integer", example = "200")),
                    SchemaProperty(name = "message", schema = Schema(type = "string", allowableValues = ["OK"])),
                    SchemaProperty(
                        name = "data",
                        schema = Schema(
                            type = "object",
                            description = "서비스 이름별 상태",
                            additionalPropertiesSchema = ServiceStatus::class,
                        ),
                    ),
                ],
            ),
        ],
    )
    @GetMapping
    fun checkAll(): Mono<ResponseEntity<Map<String, ServiceStatus>>> = routeLocator.routes
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
        .map { ResponseEntity.ok(it) }

    @Schema(
        enumAsRef = true,
        description = "상태 확인 경로가 모두 정상이면 UP, 일부만 정상이면 DEGRADED, 모두 비정상이면 DOWN입니다.",
    )
    enum class ServiceStatus {
        UP,
        DEGRADED,
        DOWN,
    }
}
