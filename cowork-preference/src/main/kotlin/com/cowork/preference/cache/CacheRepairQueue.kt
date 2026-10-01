package com.cowork.preference.cache

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val MAX_PENDING_REPAIRS = 10_000
private const val INITIAL_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
private const val MAX_BACKOFF_SHIFT = 5

/**
 * DB commit 뒤 기록·무효화에 실패한 cache key를 모아 backoff로 삭제를 재시도한다.
 * 모든 값은 [ttl]과 함께 기록되므로 마지막 실패 시점부터 [ttl]이 지나면 stale 값은 Redis에서 자연 만료된다.
 */
internal class CacheRepairQueue(private val ttl: Duration, meterRegistry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(CacheRepairQueue::class.java)
    private val pending = ConcurrentHashMap<String, PendingRepair>()
    private val outcomeCounters = RepairOutcome.entries.associateWith { outcome ->
        Counter.builder("preference.cache.repairs")
            .tag("outcome", outcome.tag)
            .register(meterRegistry)
    }
    private val convergence = Timer.builder("preference.cache.repair.convergence")
        .register(meterRegistry)

    init {
        meterRegistry.gauge("preference.cache.repair.pending", pending) { it.size.toDouble() }
    }

    fun contains(key: String): Boolean = pending.containsKey(key)

    fun schedule(key: String) {
        val now = Instant.now()
        if (!pending.containsKey(key) && pending.size >= MAX_PENDING_REPAIRS) {
            outcomeCounters.getValue(RepairOutcome.DROPPED).increment()
            log.warn("Dropped preference cache repair because the queue is full key={}", key)
            return
        }
        pending.merge(key, firstRepair(now)) { current, _ -> current.copy(staleUntil = now.plus(ttl)) }
    }

    fun markRepaired(key: String) {
        val repair = pending.remove(key) ?: return
        complete(RepairOutcome.REPAIRED, repair, Instant.now())
    }

    /**
     * 재시도 시각이 된 key를 [delete]로 삭제하고, TTL로 이미 만료됐을 key는 정리한다.
     * [canAttempt]가 거부하면 만료 정리만 하고, 삭제가 한 번 실패하면 이번 회차를 멈춰 key마다 timeout을 기다리지 않는다.
     */
    suspend fun repairDue(canAttempt: (Instant) -> Boolean, delete: suspend (String) -> Boolean) {
        for ((key, repair) in pending.entries.toList()) {
            val now = Instant.now()
            if (!now.isBefore(repair.staleUntil)) {
                if (pending.remove(key, repair)) complete(RepairOutcome.EXPIRED, repair, now)
                continue
            }
            if (now.isBefore(repair.nextAttemptAt) || !canAttempt(now)) continue
            if (delete(key)) {
                markRepaired(key)
            } else {
                pending.computeIfPresent(key) { _, current -> current.backedOff(Instant.now()) }
                return
            }
        }
    }

    private fun firstRepair(now: Instant) = PendingRepair(
        since = now,
        staleUntil = now.plus(ttl),
        attempts = 0,
        nextAttemptAt = now.plusMillis(INITIAL_BACKOFF_MS),
    )

    private fun complete(outcome: RepairOutcome, repair: PendingRepair, now: Instant) {
        outcomeCounters.getValue(outcome).increment()
        convergence.record(Duration.between(repair.since, now))
    }

    private enum class RepairOutcome(val tag: String) {
        REPAIRED("repaired"),
        EXPIRED("expired"),
        DROPPED("dropped"),
    }

    /**
     * @property since 첫 실패 시각. 수렴 시간 metric의 기준이다.
     * @property staleUntil 마지막 실패 전에 기록된 값이 TTL로 만료되는 시각. 이후에는 삭제할 필요가 없다.
     */
    private data class PendingRepair(
        val since: Instant,
        val staleUntil: Instant,
        val attempts: Int,
        val nextAttemptAt: Instant,
    ) {
        fun backedOff(now: Instant): PendingRepair {
            val backoffMs = (INITIAL_BACKOFF_MS shl attempts.coerceAtMost(MAX_BACKOFF_SHIFT))
                .coerceAtMost(MAX_BACKOFF_MS)
            return copy(attempts = attempts + 1, nextAttemptAt = now.plusMillis(backoffMs))
        }
    }
}
