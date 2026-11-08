package com.cowork.applet.global.logging

    // TODO: 나중에 테스트 라이브러리 도입하고 해당 테스트는 삭제(왜냐면 해당 테스트는 ci커버리지 측정기가 무조건 실패하는걸 방지하기 위한 비비즈니스 로직 테스 트라서)
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
