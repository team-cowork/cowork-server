package com.cowork.global.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MetricsConfig {

    @Bean
    fun commonTags(): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer { registry -> registry.config().commonTags("application", "cowork-server") }
}
