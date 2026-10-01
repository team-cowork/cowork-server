package com.cowork.preference.cache

import com.cowork.preference.domain.ResourceType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
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

private const val TTL_SECONDS = 300L
private const val EXPIRY_LOCK_KEY = "status:expiry:lock"
private const val LOCK_TTL_SECONDS = 55L
private const val OPERATION_TIMEOUT_MS = 500L

/**
 * Redis는 선택적 가속 계층이므로 조회·기록·무효화 실패를 호출자에게 전파하지 않는다.
 * DB commit 뒤 기록·무효화에 실패한 key는 [CacheRepairQueue]로 수렴시키고,
 * 연속 실패 중에는 [CacheCircuitBreaker]로 Redis 호출을 건너뛴다.
 */
class PreferenceCache(private val redis: Redis, meterRegistry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(PreferenceCache::class.java)
    private val redisApi = RedisAPI.api(redis)
    private val repairQueue = CacheRepairQueue(Duration.ofSeconds(TTL_SECONDS), meterRegistry)
    private val circuitBreaker = CacheCircuitBreaker(meterRegistry)
    private val failureCounters = CacheOperation.entries.associateWith { operation ->
        Counter.builder("preference.cache.failures")
            .tag("operation", operation.tag)
            .register(meterRegistry)
    }

    suspend fun getSettings(resourceType: ResourceType, resourceId: Long): JsonObject? {
        val key = settingKey(resourceType, resourceId)
        if (repairQueue.contains(key)) return null
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
        if (written) repairQueue.markRepaired(key) else repairQueue.schedule(key)
    }

    suspend fun invalidateSettings(resourceType: ResourceType, resourceId: Long) {
        val key = settingKey(resourceType, resourceId)
        val deleted = attempt(CacheOperation.INVALIDATE, key) { deleteSetting(key) }.isSuccess
        if (deleted) repairQueue.markRepaired(key) else repairQueue.schedule(key)
    }

    /** circuit이 열려 있으면 만료 정리만 하고 Redis 삭제는 시도하지 않는다. */
    suspend fun repairPending() {
        repairQueue.repairDue(canAttempt = { !circuitBreaker.isOpen(it) }) { key ->
            attempt(CacheOperation.REPAIR, key) { deleteSetting(key) }.isSuccess
        }
    }

    suspend fun acquireExpiryLock(): Boolean {
        val result = redisApi.set(listOf(EXPIRY_LOCK_KEY, "1", "NX", "EX", LOCK_TTL_SECONDS.toString())).coAwait()
        return result?.toString() == "OK"
    }

    private suspend fun writeSetting(key: String, settings: JsonObject) {
        redisApi.setex(key, TTL_SECONDS.toString(), settings.encode()).coAwait()
    }

    private suspend fun deleteSetting(key: String) {
        redisApi.del(listOf(key)).coAwait()
    }

    private suspend fun <T> attempt(operation: CacheOperation, target: String, block: suspend () -> T): Result<T> {
        if (circuitBreaker.isOpen()) return Result.failure(CacheCircuitBreaker.OpenException)
        return try {
            Result.success(withTimeout(OPERATION_TIMEOUT_MS) { block() }).also { circuitBreaker.recordSuccess() }
        } catch (error: TimeoutCancellationException) {
            Result.failure(recordFailure(operation, target, error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(recordFailure(operation, target, error))
        }
    }

    private fun recordFailure(operation: CacheOperation, target: String, error: Exception): Exception {
        failureCounters.getValue(operation).increment()
        log.warn("Failed to {} preference cache key={}: {}", operation.tag, target, error.toString())
        circuitBreaker.recordFailure()
        return error
    }

    private fun settingKey(resourceType: ResourceType, resourceId: Long) = "pref:${resourceType.name}:$resourceId"

    private enum class CacheOperation(val tag: String) {
        GET("get"),
        SET("set"),
        SET_BULK("set_bulk"),
        INVALIDATE("invalidate"),
        REPAIR("repair"),
    }
}
