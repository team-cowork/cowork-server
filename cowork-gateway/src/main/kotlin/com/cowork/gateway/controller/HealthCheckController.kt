package com.cowork.gateway.controller

import com.cowork.gateway.health.ServiceStatus
import com.cowork.gateway.response.CommonApiResponse
import com.netflix.appinfo.InstanceInfo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.netflix.eureka.EurekaServiceInstance
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@Tag(name = "Health", description = "서비스 헬스 체크 API")
@RestController
@RequestMapping("/api/health")
class HealthCheckController(
    private val discoveryClient: ReactiveDiscoveryClient,
    private val routeLocator: RouteLocator,
) {

    @Operation(
        summary = "전체 서비스 상태 조회",
        description = """게이트웨이 라우트에 등록된 모든 서비스의 상태를 반환합니다.

- **UP**: 모든 인스턴스가 정상
- **DEGRADED**: 일부 인스턴스가 비정상 (부분 장애)
- **DOWN**: Eureka 미등록 또는 모든 인스턴스 비정상""",
    )
    @ApiResponse(
        responseCode = "200",
        description = "서비스 상태 목록",
        content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = CommonApiResponse::class),
            examples = [ExampleObject(
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
                }"""
            )]
        )]
    )
    @GetMapping
    fun checkAll(): Mono<ResponseEntity<CommonApiResponse<Map<String, ServiceStatus>>>> {
        return routeLocator.routes
            .map { route -> route.uri }
            .filter { it.scheme == "lb" }
            .map { it.host.lowercase() }
            .distinct()
            .collectList()
            .flatMap { serviceIds ->
                discoveryClient.services.collectList().map { registeredIds ->
                    serviceIds to registeredIds.map { it.lowercase() }.toSet()
                }
            }
            .flatMapMany { (serviceIds, registeredIds) ->
                reactor.core.publisher.Flux.fromIterable(serviceIds).flatMap { serviceId ->
                    if (serviceId !in registeredIds) {
                        Mono.just(serviceId to ServiceStatus.DOWN)
                    } else {
                        discoveryClient.getInstances(serviceId)
                            .collectList()
                            .map { instances ->
                                val status = when {
                                    instances.isEmpty() || instances.none { it.isUp() } -> ServiceStatus.DOWN
                                    instances.all { it.isUp() } -> ServiceStatus.UP
                                    else -> ServiceStatus.DEGRADED
                                }
                                serviceId to status
                            }
                    }
                }
            }
            .collectMap({ it.first }, { it.second })
            .map { ResponseEntity.ok(CommonApiResponse.success(it)) }
    }

    private fun ServiceInstance.isUp(): Boolean {
        if (this is EurekaServiceInstance) {
            return instanceInfo.status == InstanceInfo.InstanceStatus.UP
        }
        return false
    }
}
