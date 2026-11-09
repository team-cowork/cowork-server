package com.cowork.gateway.response.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize

@Component
@ConfigurationProperties(prefix = "gateway.response-wrapper")
class ApiResponseWrapperProperties {
    var maxErrorBodySize: DataSize = DataSize.ofKilobytes(64)

    var skipPatterns: List<String> = listOf(
        "/actuator/**",
        // Production CD reads the raw deployment state from inside the allocation.
        "/internal/**",
        "/fallback",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/webjars/**",
        "/api/chat/graphql",
        "/api/chat/asyncapi.json",
    )

    fun maxErrorBodyBytes(): Int = maxErrorBodySize.toBytes().also {
        require(it in 1..Int.MAX_VALUE.toLong()) {
            "gateway.response-wrapper.max-error-body-size must be between 1B and ${Int.MAX_VALUE}B"
        }
    }.toInt()
}
