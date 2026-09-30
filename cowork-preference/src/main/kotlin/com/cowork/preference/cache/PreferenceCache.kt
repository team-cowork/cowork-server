package com.cowork.preference.cache

import com.cowork.preference.domain.ResourceType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.redis.client.Command
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisAPI
import io.vertx.redis.client.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TTL_SECONDS = 300L
private const val EXPIRY_LOCK_KEY = "status:expiry:lock"
private const val LOCK_TTL_SECONDS = 55L
private const val OPERATION_TIMEOUT_MS = 500L
private const val MAX_PENDING_REPAIRS = 10_000
private const val REPAIR_INITIAL_BACKOFF_MS = 1_000L
private const val REPAIR_MAX_BACKOFF_MS = 30_000L
private const val CIRCUIT_FAILURE_THRESHOLD = 5
private const val CIRCUIT_OPEN_MS = 5_000L

/**
 * Redis는 선택적 가속 계층이므로 조회·기록·무효화 실패를 호출자에게 전파하지 않는다.
 * DB commit 뒤 기록·무효화에 실패한 key는 repair 대상으로 등록해 backoff로 삭제를 재시도한다.
 * 모든 값은 TTL과 함께 기록되므로 마지막 실패 시점부터 TTL이 지나면 stale 값은 Redis에서 자연 만료된다.
 * 연속 실패가 기준을 넘으면 circuit을 열어 일정 시간 Redis 호출을 건너뛰므로 요청마다 timeout을 기다리지 않는다.
 * 열린 뒤 첫 호출이 다시 실패하면 즉시 다시 열고, 성공하면 닫는다.
 */
class PreferenceCache(private val redis: Redis, meterRegistry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(PreferenceCache::class.java)
    private val redisApi = RedisAPI.api(redis)
    private val pendingRepairs = ConcurrentHashMap<String, PendingRepair>()
    private val failureCounters = CacheOperation.entries.associateWith { operation ->
        Counter.builder("preference.cache.failures")
            .tag("operation", operation.tag)
            .register(meterRegistry)
    }
    private val repairCounters = RepairOutcome.entries.associateWith { outcome ->
        Counter.builder("preference.cache.repairs")
            .tag("outcome", outcome.tag)
            .register(meterRegistry)
    }
    private val repairConvergence = Timer.builder("preference.cache.repair.convergence")
        .register(meterRegistry)
    private val consecutiveFailures = AtomicInteger()

    @Volatile
    private var circuitOpenUntil: Instant = Instant.EPOCH

    @Volatile
    private var circuitOpened = false

    init {
        meterRegistry.gauge("preference.cache.repair.pending", pendingRepairs) { it.size.toDouble() }
        meterRegistry.gauge("preference.cache.circuit.open", this) { if (it.isCircuitOpen()) 1.0 else 0.0 }
    }

    suspend fun getSettings(resourceType: ResourceType, resourceId: Long): JsonObject? {
        val key = settingKey(resourceType, resourceId)
        if (pendingRepairs.containsKey(key)) return null
        return attempt(CacheOperation.GET, key) {
            redisApi.get(key).coAwait()?.toString()?.let { JsonObject(it) }
        }.getOrNull()
    }

    /**
     * DB에서 읽은 값을 채운다. 실패해도 stale 값을 만들지 않으므로 repair 대상으로 등록하지 않는다.
     * 동시 쓰기보다 먼저 읽은 값일 수 있으므로 repair 대상 key를 해제하지도 않는다.
     */
    suspend fun populateSettings(resourceType: ResourceType, resourceId: Long, settings: JsonObject) {
        val key = settingKey(resourceType, resourceId)
        attempt(CacheOperation.SET, key) { writeSetting(key, settings) }
    }

    /** DB에서 읽은 여러 값을 한 번의 pipeline으로 채운다. */
    suspend fun populateSettingsBulk(resourceType: ResourceType, settingsById: Map<Long, JsonObject>) {
        if (settingsById.isEmpty()) return
        val requests = settingsById.map { (resourceId, settings) ->
            Request.cmd(Command.SET)
                .arg(settingKey(resourceType, resourceId))
                .arg(settings.encode())
                .arg("EX")
                .arg(TTL_SECONDS)
        }
        val target = "pref:${resourceType.name}:*(${requests.size})"
        attempt(CacheOperation.SET_BULK, target) { redis.batch(requests).coAwait() }
    }

    /** DB commit으로 바뀐 값을 기록한다. 실패하면 이전 값이 남아 있을 수 있으므로 repair 대상으로 등록한다. */
    suspend fun setSettings(resourceType: ResourceType, resourceId: Long, settings: JsonObject) {
        val key = settingKey(resourceType, resourceId)
        val written = attempt(CacheOperation.SET, key) { writeSetting(key, settings) }.isSuccess
        if (written) markRepaired(key) else scheduleRepair(key)
    }

    suspend fun invalidateSettings(resourceType: ResourceType, resourceId: Long) {
        val key = settingKey(resourceType, resourceId)
        val deleted = attempt(CacheOperation.INVALIDATE, key) { deleteSetting(key) }.isSuccess
        if (deleted) markRepaired(key) else scheduleRepair(key)
    }

    /**
     * 재시도 시각이 된 repair key를 삭제하고, TTL로 이미 만료됐을 key는 정리한다.
     * circuit이 열려 있으면 만료 정리만 하고, 삭제가 한 번 실패하면 이번 회차를 멈춰 key마다 timeout을 기다리지 않는다.
     */
    suspend fun repairPending() {
        for ((key, repair) in pendingRepairs.entries.toList()) {
            val now = Instant.now()
            if (!now.isBefore(repair.staleUntil)) {
                if (pendingRepairs.remove(key, repair)) complete(RepairOutcome.EXPIRED, repair, now)
                continue
            }
            if (now.isBefore(repair.nextAttemptAt) || isCircuitOpen(now)) continue
            if (attempt(CacheOperation.REPAIR, key) { deleteSetting(key) }.isSuccess) {
                markRepaired(key)
            } else {
                pendingRepairs.computeIfPresent(key) { _, current -> current.backedOff(Instant.now()) }
                return
            }
        }
    }

    /**
     * Redis를 쓸 수 없으면 lock 없이 진행한다. 만료 대상 행은 DB `FOR UPDATE`로 잠기고 처리 시 만료 시각이 지워지므로,
     * 여러 실행이 겹쳐도 같은 계정을 두 번 처리하지 않는다.
     */
    suspend fun acquireExpiryLock(): Boolean = attempt(CacheOperation.LOCK, EXPIRY_LOCK_KEY) {
        redisApi.set(listOf(EXPIRY_LOCK_KEY, "1", "NX", "EX", LOCK_TTL_SECONDS.toString())).coAwait()
            ?.toString() == "OK"
    }.getOrDefault(true)

    private suspend fun writeSetting(key: String, settings: JsonObject) {
        redisApi.setex(key, TTL_SECONDS.toString(), settings.encode()).coAwait()
    }

    private suspend fun deleteSetting(key: String) {
        redisApi.del(listOf(key)).coAwait()
    }

    private suspend fun <T> attempt(operation: CacheOperation, target: String, block: suspend () -> T): Result<T> {
        if (isCircuitOpen()) return Result.failure(CircuitOpenException)
        return try {
            Result.success(withTimeout(OPERATION_TIMEOUT_MS) { block() }).also { recordSuccess() }
        } catch (error: TimeoutCancellationException) {
            Result.failure(recordFailure(operation, target, error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(recordFailure(operation, target, error))
        }
    }

    private fun isCircuitOpen(now: Instant = Instant.now()): Boolean = now.isBefore(circuitOpenUntil)

    private fun recordSuccess() {
        consecutiveFailures.set(0)
        if (!circuitOpened) return
        circuitOpened = false
        log.info("Closed preference cache circuit after Redis recovered")
    }

    private fun recordFailure(operation: CacheOperation, target: String, error: Exception): Exception {
        failureCounters.getValue(operation).increment()
        log.warn("Failed to {} preference cache key={}: {}", operation.tag, target, error.toString())
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
            circuitOpenUntil = Instant.now().plusMillis(CIRCUIT_OPEN_MS)
            if (!circuitOpened) {
                circuitOpened = true
                log.warn(
                    "Opened preference cache circuit for {}ms after {} consecutive failures",
                    CIRCUIT_OPEN_MS,
                    failures,
                )
            }
        }
        return error
    }

    private fun scheduleRepair(key: String) {
        val now = Instant.now()
        val existing = pendingRepairs[key]
        if (existing == null && pendingRepairs.size >= MAX_PENDING_REPAIRS) {
            repairCounters.getValue(RepairOutcome.DROPPED).increment()
            log.warn("Dropped preference cache repair because the queue is full key={}", key)
            return
        }
        pendingRepairs.merge(key, PendingRepair.first(now)) { current, _ -> current.failedAgain(now) }
    }

    private fun markRepaired(key: String) {
        val repair = pendingRepairs.remove(key) ?: return
        complete(RepairOutcome.REPAIRED, repair, Instant.now())
    }

    private fun complete(outcome: RepairOutcome, repair: PendingRepair, now: Instant) {
        repairCounters.getValue(outcome).increment()
        repairConvergence.record(Duration.between(repair.since, now))
    }

    private fun settingKey(resourceType: ResourceType, resourceId: Long) = "pref:${resourceType.name}:$resourceId"

    private object CircuitOpenException : Exception("preference cache circuit is open")

    private enum class CacheOperation(val tag: String) {
        GET("get"),
        SET("set"),
        SET_BULK("set_bulk"),
        INVALIDATE("invalidate"),
        REPAIR("repair"),
        LOCK("lock"),
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
        fun failedAgain(now: Instant) = copy(staleUntil = now.plusSeconds(TTL_SECONDS))

        fun backedOff(now: Instant): PendingRepair {
            val backoffMs = (REPAIR_INITIAL_BACKOFF_MS shl attempts.coerceAtMost(MAX_BACKOFF_SHIFT))
                .coerceAtMost(REPAIR_MAX_BACKOFF_MS)
            return copy(attempts = attempts + 1, nextAttemptAt = now.plusMillis(backoffMs))
        }

        companion object {
            private const val MAX_BACKOFF_SHIFT = 5

            fun first(now: Instant) = PendingRepair(
                since = now,
                staleUntil = now.plusSeconds(TTL_SECONDS),
                attempts = 0,
                nextAttemptAt = now.plusMillis(REPAIR_INITIAL_BACKOFF_MS),
            )
        }
    }
}
