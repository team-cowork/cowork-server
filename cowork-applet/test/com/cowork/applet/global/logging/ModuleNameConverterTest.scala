package com.cowork.applet.global.logging

// TODO: 비즈니스 로직 테스트가 생기면 삭제한다. run-jvm.sh의 validate_tests가 테스트 0개를 실패로 처리해서 CI 커버리지 검증을 통과시키려고 둔 비즈니스 외 테스트다.
class ModuleNameConverterTest extends munit.FunSuite:

  test("moduleOf / domain 패키지 로거 / 기능 이름을 반환한다") {
    // Given
    val loggerName = "com.cowork.applet.domain.review.service.ReviewService"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "review")
  }

  test("moduleOf / global 패키지 로거 / 기능 이름을 반환한다") {
    // Given
    val loggerName = "com.cowork.applet.global.metrics.HttpMetricsFilter"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "metrics")
  }

  test("moduleOf / 외부 로거 / 바로 위 패키지 이름을 반환한다") {
    // Given
    val loggerName = "play.api.Play"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "api")
  }

  test("moduleOf / 패키지 없는 로거 / 로거 이름을 반환한다") {
    // Given
    val loggerName = "application"

    // When
    val module = ModuleNameConverter.moduleOf(loggerName)

    // Then
    assertEquals(module, "application")
  }
