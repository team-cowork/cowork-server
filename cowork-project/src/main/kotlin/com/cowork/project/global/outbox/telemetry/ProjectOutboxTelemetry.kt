package com.cowork.project.global.outbox.telemetry

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.atomic.AtomicLong

internal class ProjectOutboxTelemetry(private val meterRegistry: MeterRegistry) {
    private val pendingGauge = AtomicLong()
    private val oldestSecondsGauge = AtomicLong()
    private val quarantinedGauge = AtomicLong()
    private val observationSuccessGauge = AtomicLong()
    private val publishTimer =
        Timer
            .builder("cowork.kafka.outbox.publish")
            .description("Kafka outbox publish acknowledgement latency")
            .register(meterRegistry)
    private val publishedCounter =
        Counter
            .builder("cowork.kafka.outbox.published")
            .description("Kafka outbox rows deleted after publication")
            .register(meterRegistry)
    private val quarantinedCounter =
        Counter
            .builder("cowork.kafka.outbox.quarantined")
            .description("Kafka outbox rows newly quarantined")
            .register(meterRegistry)

    init {
        Gauge
            .builder("cowork.kafka.outbox.pending", pendingGauge) { it.get().toDouble() }
            .description("Shared database Kafka outbox rows waiting for publication")
            .register(meterRegistry)
        Gauge
            .builder("cowork.kafka.outbox.oldest.seconds", oldestSecondsGauge) { it.get().toDouble() }
            .description("Age in seconds of the oldest pending Kafka outbox row")
            .register(meterRegistry)
        Gauge
            .builder("cowork.kafka.outbox.quarantined.current", quarantinedGauge) { it.get().toDouble() }
            .description("Shared database Kafka outbox rows currently quarantined")
            .register(meterRegistry)
        Gauge
            .builder("cowork.kafka.outbox.observation.success", observationSuccessGauge) { it.get().toDouble() }
            .description("Whether the latest Kafka outbox backlog observation succeeded")
            .register(meterRegistry)
    }

    fun startPublish(): Timer.Sample = Timer.start(meterRegistry)

    fun stopPublish(sample: Timer.Sample) {
        sample.stop(publishTimer)
    }

    fun published() {
        publishedCounter.increment()
    }

    fun failure(type: String) {
        meterRegistry.counter("cowork.kafka.outbox.failures", "failure_type", type).increment()
    }

    fun retry(type: String) {
        meterRegistry.counter("cowork.kafka.outbox.retries", "failure_type", type).increment()
    }

    fun quarantined() {
        quarantinedCounter.increment()
    }

    fun observe(pending: Long, oldestSeconds: Long, quarantined: Long) {
        pendingGauge.set(pending)
        oldestSecondsGauge.set(oldestSeconds)
        quarantinedGauge.set(quarantined)
        observationSuccessGauge.set(1)
    }

    fun observationFailed() {
        observationSuccessGauge.set(0)
    }
}
