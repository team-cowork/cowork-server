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
 * DB commit 뒤 기록에 실패한 값을 모아 backoff로 version 비교 기록을 재시도한다.
 * key를 지우면 그 사이 늦게 도착한 이전 version이 빈 key를 채울 수 있으므로 삭제하지 않고 다시 기록한다.
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

    /** 같은 key에 여러 번 실패하면 가장 새로운 version만 다시 기록한다. */
    fun schedule(key: String, settings: VersionedSettings) {
        val now = Instant.now()
        if (!pending.containsKey(key) && pending.size >= MAX_PENDING_REPAIRS) {
            outcomeCounters.getValue(RepairOutcome.DROPPED).increment()
            log.warn("Dropped preference cache repair because the queue is full key={}", key)
            return
        }
        pending.merge(key, firstRepair(now, settings)) { current, _ ->
            val newest = if (settings.version > current.settings.version) settings else current.settings
            current.copy(staleUntil = now.plus(ttl), settings = newest)
        }
    }

    /** [written] 이상 version이 기록됐을 때만 해제해, 더 새로운 실패 값의 재기록을 놓치지 않는다. */
    fun markRepaired(key: String, written: Instant) {
        val repair = pending[key] ?: return
        if (repair.settings.version > written) return
        if (pending.remove(key, repair)) complete(RepairOutcome.REPAIRED, repair, Instant.now())
    }

    /**
     * 재시도 시각이 된 key에 [write]로 마지막 실패 값을 다시 기록하고, TTL로 이미 만료됐을 key는 정리한다.
     * [canAttempt]가 거부하면 만료 정리만 하고, 기록이 한 번 실패하면 이번 회차를 멈춰 key마다 timeout을 기다리지 않는다.
     */
    suspend fun repairDue(canAttempt: (Instant) -> Boolean, write: suspend (String, VersionedSettings) -> Boolean) {
        for ((key, repair) in pending.entries.toList()) {
            val now = Instant.now()
            if (!now.isBefore(repair.staleUntil)) {
                if (pending.remove(key, repair)) complete(RepairOutcome.EXPIRED, repair, now)
                continue
            }
            if (now.isBefore(repair.nextAttemptAt) || !canAttempt(now)) continue
            if (write(key, repair.settings)) {
                markRepaired(key, repair.settings.version)
            } else {
                pending.computeIfPresent(key) { _, current -> current.backedOff(Instant.now()) }
                return
            }
        }
    }

    private fun firstRepair(now: Instant, settings: VersionedSettings) = PendingRepair(
        settings = settings,
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
     * @property settings 다시 기록할 값. 실패한 값 중 가장 새로운 version이다.
     * @property since 첫 실패 시각. 수렴 시간 metric의 기준이다.
     * @property staleUntil 마지막 실패 전에 기록된 값이 TTL로 만료되는 시각. 이후에는 다시 기록할 필요가 없다.
     */
    private data class PendingRepair(
        val settings: VersionedSettings,
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
