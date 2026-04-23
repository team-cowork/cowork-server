package com.cowork.preference

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

object MetricsRegistry {
    lateinit var registry: PrometheusMeterRegistry
}
