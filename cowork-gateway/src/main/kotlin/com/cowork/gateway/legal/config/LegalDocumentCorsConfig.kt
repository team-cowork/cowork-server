package com.cowork.gateway.legal.config

import org.springframework.cloud.gateway.config.GlobalCorsProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

// Gateway globalcors applies only to routed requests. Annotated controllers need their own filter,
// so this one reuses the global all-path policy for the legal document endpoints.
@Configuration
class LegalDocumentCorsConfig {
    @Bean
    fun legalDocumentCorsWebFilter(globalCorsProperties: GlobalCorsProperties): CorsWebFilter =
        CorsWebFilter(legalDocumentCorsSource(globalCorsProperties))

    companion object {
        internal fun legalDocumentCorsSource(globalCorsProperties: GlobalCorsProperties) =
            UrlBasedCorsConfigurationSource().apply {
                globalCorsProperties.corsConfigurations["/**"]?.let {
                    registerCorsConfiguration("/api/legal/**", it)
                }
            }
    }
}
