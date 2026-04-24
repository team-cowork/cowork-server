package com.cowork.gateway.controller

import com.cowork.gateway.response.CommonApiResponse
import com.netflix.appinfo.InstanceInfo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
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
    private val discoveryClient: ReactiveDiscoveryClient
) {

    @Operation(
        summary = "전체 서비스 상태 조회",
        description = """Eureka에 등록된 모든 서비스의 인스턴스 상태를 집계하여 반환합니다.

- **UP**: 모든 인스턴스가 정상
- **DEGRADED**: 일부 인스턴스가 비정상 (부분 장애)
- **DOWN**: 등록된 인스턴스 없음""",
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
    fun checkAll(): Mono<ResponseEntity<CommonApiResponse<Map<String, String>>>> {
        return discoveryClient.services
            .flatMap { serviceId ->
                discoveryClient.getInstances(serviceId)
                    .collectList()
                    .map { instances ->
                        val status = when {
                            instances.isEmpty() || instances.none { it.isUp() } -> "DOWN"
                            instances.all { it.isUp() } -> "UP"
                            else -> "DEGRADED"
                        }
                        serviceId to status
                    }
            }
            .collectMap({ it.first }, { it.second })
            .map { ResponseEntity.ok(CommonApiResponse.success(it)) }
    }

    private fun org.springframework.cloud.client.ServiceInstance.isUp(): Boolean {
        if (this is EurekaServiceInstance) {
            return instanceInfo.status == InstanceInfo.InstanceStatus.UP
        }
        return true
    }
}
