package com.cowork.gateway.filter

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize

@Component
@ConfigurationProperties(prefix = "gateway.response-wrapper")
class ApiResponseWrapperProperties {
    var maxWrappableSize: DataSize = DataSize.ofMegabytes(1)

    var skipPatterns: List<String> = listOf(
        "/actuator/**",
        "/fallback",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/webjars/**",
        "/api/chat/graphql",
        "/api/chat/asyncapi.json",
    )

    fun maxWrappableBytes(): Int = maxWrappableSize.toBytes().also {
        require(it in 1..Int.MAX_VALUE.toLong()) {
            "gateway.response-wrapper.max-wrappable-size must be between 1B and ${Int.MAX_VALUE}B"
        }
    }.toInt()
}
