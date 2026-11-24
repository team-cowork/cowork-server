package com.cowork.applet.global.ratelimit

class FixedWindowRateLimiterTest extends munit.FunSuite:

  test("tryAcquire / 한 구간에서 한도까지 / 허용하고 넘으면 거부한다") {
    // Given
    var now = 0L
    val limiter = FixedWindowRateLimiter(2, 1000, () => now)

    // When
    val results = Seq.fill(3)(limiter.tryAcquire("a"))

    // Then
    assertEquals(results, Seq(true, true, false))
  }

  test("tryAcquire / 구간이 지난 뒤 / 다시 허용한다") {
    // Given
    var now = 0L
    val limiter = FixedWindowRateLimiter(1, 1000, () => now)
    limiter.tryAcquire("a")

    // When
    now = 1000
    val allowed = limiter.tryAcquire("a")

    // Then
    assert(allowed)
  }

  test("tryAcquire / 다른 키 / 따로 센다") {
    // Given
    val limiter = FixedWindowRateLimiter(1, 1000, () => 0L)
    limiter.tryAcquire("a")

    // When
    val allowed = limiter.tryAcquire("b")

    // Then
    assert(allowed)
  }
