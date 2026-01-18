package com.cowork.global.ratelimit

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 키마다 고정된 시간 창 안의 요청 수를 센다. 단일 인스턴스 운영을 전제로 메모리에만 보관한다.
 */
class RateLimiter(
    private val limit: Int,
    private val window: Duration,
    private val clock: Clock,
) {
    private val windows = ConcurrentHashMap<String, Window>()

    fun tryAcquire(key: String): Boolean {
        val now = clock.millis()
        val current = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startedAt >= window.toMillis()) Window(now, 1) else existing.next()
        }!!
        return current.count <= limit
    }

    fun evictExpired() {
        val now = clock.millis()
        windows.entries.removeIf { now - it.value.startedAt >= window.toMillis() }
    }

    fun size(): Int = windows.size

    private data class Window(val startedAt: Long, val count: Int) {
        fun next() = copy(count = count + 1)
    }
}
