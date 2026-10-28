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
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val TTL_SECONDS = 300L
private const val EXPIRY_LOCK_KEY = "status:expiry:lock"
private const val LOCK_TTL_SECONDS = 55L
private const val OPERATION_TIMEOUT_MS = 500L

/** version 없는 값을 쓰는 구형 인스턴스와 key를 나눠, 그 기록이 version 비교 없이 새 인스턴스에 읽히지 않게 한다. */
private const val KEY_PREFIX = "pref:v2:"

/** 구형 인스턴스가 읽는 key다. 모든 인스턴스가 [KEY_PREFIX]를 쓰게 되면 이 key 삭제와 함께 제거한다. */
private const val LEGACY_KEY_PREFIX = "pref:"

/**
 * KEYS[1]의 version이 ARGV[1]보다 작을 때만 settings를 바꾼다. 같은 version은 같은 DB 상태이므로 그대로 둔다.
 * KEYS[2]가 있으면 구형 인스턴스가 읽는 unversioned key를 지운다.
 */
private const val WRITE_IF_NEWER_SCRIPT = """
for i = 2, #KEYS do redis.call('DEL', KEYS[i]) end
local current = redis.call('HGET', KEYS[1], 'version')
if current and tonumber(current) >= tonumber(ARGV[1]) then return 0 end
redis.call('HSET', KEYS[1], 'version', ARGV[1], 'settings', ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
"""

/**
 * Redis는 선택적 가속 계층이므로 조회·기록 실패를 호출자에게 전파하지 않는다.
 * DB commit 뒤 기록에 실패한 값은 [CacheRepairQueue]로 다시 기록하고,
 * 연속 실패 중에는 [CacheCircuitBreaker]로 Redis 호출을 건너뛴다.
 *
 * 모든 기록은 DB의 `state_occurred_at`을 version으로 함께 저장하고, 캐시에 더 새로운 version이 있으면 버린다.
 * 삭제·만료도 key를 지우지 않고 그 결과를 새 version으로 기록하므로, version을 비교할 대상이 사라지지 않는다.
 * 따라서 늦게 도착한 조회 결과나 이전 변경의 기록은 TTL 안에서 최신 설정을 되돌리지 않는다.
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
            redisApi.hget(key, "settings").coAwait()?.toString()?.let { JsonObject(it) }
        }.getOrNull()
    }

    /**
     * DB에서 읽은 값을 채운다. 실패해도 stale 값을 만들지 않으므로 repair 대상으로 등록하지 않는다.
     * 동시 쓰기보다 먼저 읽은 값일 수 있으므로 repair 대상 key를 해제하지도 않는다.
     */
    suspend fun populateSettings(resourceType: ResourceType, resourceId: Long, settings: VersionedSettings) {
        val key = settingKey(resourceType, resourceId)
        attempt(CacheOperation.SET, key) { redis.send(writeIfNewer(listOf(key), settings)).coAwait() }
    }

    /** DB에서 읽은 여러 값을 한 번의 pipeline으로 채운다. */
    suspend fun populateSettingsBulk(resourceType: ResourceType, settingsById: Map<Long, VersionedSettings>) {
        if (settingsById.isEmpty()) return
        val requests = settingsById.map { (resourceId, settings) ->
            writeIfNewer(listOf(settingKey(resourceType, resourceId)), settings)
        }
        val target = "$KEY_PREFIX${resourceType.name}:*(${requests.size})"
        attempt(CacheOperation.SET_BULK, target) { redis.batch(requests).coAwait() }
    }

    /**
     * DB commit으로 바뀐 값을 기록한다. 실패하면 이전 값이 남아 있을 수 있으므로 repair 대상으로 등록한다.
     * 더 새로운 version이 이미 있어 기록하지 않은 경우도 이전 값이 남지 않으므로 성공으로 본다.
     */
    suspend fun setSettings(resourceType: ResourceType, resourceId: Long, settings: VersionedSettings) {
        val key = settingKey(resourceType, resourceId)
        val written = attempt(CacheOperation.SET, key) { writeCommitted(key, settings) }.isSuccess
        if (written) repairQueue.markRepaired(key, settings.version) else repairQueue.schedule(key, settings)
    }

    /** circuit이 열려 있으면 만료 정리만 하고 Redis 기록은 시도하지 않는다. */
    suspend fun repairPending() {
        repairQueue.repairDue(canAttempt = { !circuitBreaker.isOpen(it) }) { key, settings ->
            attempt(CacheOperation.REPAIR, key) { writeCommitted(key, settings) }.isSuccess
        }
    }

    /**
     * lock은 replica 간 중복 조회를 줄이는 최적화이므로 Redis를 쓸 수 없으면 lock 없이 진행하도록 true를 반환한다.
     * 겹친 만료 실행은 outbox advisory lock과 `FOR UPDATE` 조회가 직렬화한다.
     */
    suspend fun acquireExpiryLock(): Boolean = attempt(CacheOperation.EXPIRY_LOCK, EXPIRY_LOCK_KEY) {
        redisApi.set(listOf(EXPIRY_LOCK_KEY, "1", "NX", "EX", LOCK_TTL_SECONDS.toString())).coAwait()
            ?.toString() == "OK"
    }.getOrDefault(true)

    private fun writeIfNewer(keys: List<String>, settings: VersionedSettings): Request {
        val request = Request.cmd(Command.EVAL).arg(WRITE_IF_NEWER_SCRIPT).arg(keys.size)
        keys.forEach { request.arg(it) }
        return request
            .arg(ChronoUnit.MICROS.between(Instant.EPOCH, settings.version))
            .arg(settings.settings.encode())
            .arg(TTL_SECONDS)
    }

    /** commit된 값을 기록하면서 구형 인스턴스가 읽는 key는 지운다. */
    private suspend fun writeCommitted(key: String, settings: VersionedSettings) {
        val legacyKey = LEGACY_KEY_PREFIX + key.removePrefix(KEY_PREFIX)
        redis.send(writeIfNewer(listOf(key, legacyKey), settings)).coAwait()
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

    private fun settingKey(resourceType: ResourceType, resourceId: Long) = "$KEY_PREFIX${resourceType.name}:$resourceId"

    private enum class CacheOperation(val tag: String) {
        GET("get"),
        SET("set"),
        SET_BULK("set_bulk"),
        REPAIR("repair"),
        EXPIRY_LOCK("expiry_lock"),
    }
}

/** [version]은 DB의 `state_occurred_at`이며 같은 행을 변경할 때마다 커진다. */
data class VersionedSettings(val settings: JsonObject, val version: Instant)
