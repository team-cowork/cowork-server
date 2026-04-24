package com.cowork.gateway.controller

import com.cowork.gateway.response.CommonApiResponse
import com.netflix.appinfo.InstanceInfo
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import org.springframework.cloud.netflix.eureka.EurekaServiceInstance
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/health")
class HealthCheckController(
    private val discoveryClient: ReactiveDiscoveryClient
) {

    @GetMapping
    fun checkAll(): Mono<ResponseEntity<CommonApiResponse<Map<String, String>>>> {
        return discoveryClient.services
            .flatMap { serviceId ->
                discoveryClient.getInstances(serviceId)
                    .collectList()
                    .map { instances ->
                        val status = when {
                            instances.isEmpty() -> "DOWN"
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
