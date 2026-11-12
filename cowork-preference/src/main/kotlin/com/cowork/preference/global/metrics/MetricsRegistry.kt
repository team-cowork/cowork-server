package com.cowork.preference.global.metrics

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

object MetricsRegistry {
    lateinit var registry: PrometheusMeterRegistry
}
