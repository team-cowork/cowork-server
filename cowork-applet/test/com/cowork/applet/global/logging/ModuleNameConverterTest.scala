package com.cowork.applet.global.logging

// TODO: 첫 비즈니스 로직 테스트가 생기면 삭제한다. CI 커버리지 측정이 테스트 0개로 실패하지 않게 둔 비즈니스 외 테스트다.
class ModuleNameConverterTest extends munit.FunSuite:

  test("moduleOf는 domain 패키지의 로거에서 기능 이름을 반환한다") {
    // Given
    val loggerName = "com.cowork.applet.domain.review.service.ReviewService"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "review")
  }

  test("moduleOf는 global 패키지의 로거에서 기능 이름을 반환한다") {
    // Given
    val loggerName = "com.cowork.applet.global.metrics.HttpMetricsFilter"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "metrics")
  }

  test("moduleOf는 외부 로거에서 바로 위 패키지 이름을 반환한다") {
    // Given
    val loggerName = "play.api.Play"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "api")
  }

  test("moduleOf는 패키지가 없는 로거에서 로거 이름을 반환한다") {
    // Given
    val loggerName = "application"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "application")
  }
