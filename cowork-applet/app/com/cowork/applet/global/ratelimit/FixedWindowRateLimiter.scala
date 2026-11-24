package com.cowork.applet.global.ratelimit

import java.util.concurrent.ConcurrentHashMap

// Counts per application instance. With several replicas the effective limit is the limit times the replica count.
class FixedWindowRateLimiter(limit: Int, windowMillis: Long, now: () => Long):

  private final case class Window(start: Long, count: Int)

  private val windows = ConcurrentHashMap[String, Window]()

  def tryAcquire(key: String): Boolean =
    val current = now()
    if windows.size > FixedWindowRateLimiter.MaxKeys then windows.values.removeIf(w => current - w.start >= windowMillis)
    val window = windows.compute(
      key,
      (_, existing) =>
        if existing == null || current - existing.start >= windowMillis then Window(current, 1)
        else existing.copy(count = existing.count + 1)
    )
    window.count <= limit

object FixedWindowRateLimiter:
  private val MaxKeys = 10000
