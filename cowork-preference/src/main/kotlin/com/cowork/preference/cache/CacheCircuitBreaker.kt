package com.cowork.preference.cache

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private const val FAILURE_THRESHOLD = 5
private const val OPEN_MS = 5_000L

/**
 * 연속 실패가 기준을 넘으면 일정 시간 Redis 호출을 건너뛰게 해 요청마다 timeout을 기다리지 않게 한다.
 * 열린 시간이 지난 뒤 첫 호출이 다시 실패하면 즉시 다시 열고, 성공하면 닫는다.
 */
internal class CacheCircuitBreaker(meterRegistry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(CacheCircuitBreaker::class.java)
    private val consecutiveFailures = AtomicInteger()

    @Volatile
    private var openUntil: Instant = Instant.EPOCH

    @Volatile
    private var opened = false

    init {
        meterRegistry.gauge("preference.cache.circuit.open", this) { if (it.isOpen()) 1.0 else 0.0 }
    }

    fun isOpen(now: Instant = Instant.now()): Boolean = now.isBefore(openUntil)

    fun recordSuccess() {
        consecutiveFailures.set(0)
        if (!opened) return
        opened = false
        log.info("Closed preference cache circuit after Redis recovered")
    }

    fun recordFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures < FAILURE_THRESHOLD) return
        openUntil = Instant.now().plusMillis(OPEN_MS)
        if (opened) return
        opened = true
        log.warn("Opened preference cache circuit for {}ms after {} consecutive failures", OPEN_MS, failures)
    }

    object OpenException : Exception("preference cache circuit is open")
}
