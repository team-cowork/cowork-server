package com.cowork.applet.global.logging

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
