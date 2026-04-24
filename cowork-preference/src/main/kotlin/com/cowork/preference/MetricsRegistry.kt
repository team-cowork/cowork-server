package com.cowork.preference

import io.micrometer.prometheus.PrometheusMeterRegistry

object MetricsRegistry {
    lateinit var registry: PrometheusMeterRegistry
}
