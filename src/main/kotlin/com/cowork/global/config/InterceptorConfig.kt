package com.cowork.global.config

import com.cowork.global.logging.RequestTimingInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class InterceptorConfig(
    private val requestTimingInterceptor: RequestTimingInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(requestTimingInterceptor)
            .excludePathPatterns("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**", "/notifications/stream")
    }
}
