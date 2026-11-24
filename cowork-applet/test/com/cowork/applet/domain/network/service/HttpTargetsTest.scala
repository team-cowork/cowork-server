package com.cowork.applet.domain.network.service

import com.cowork.applet.global.error.ExpectedException

class HttpTargetsTest extends munit.FunSuite:

  test("host / 공개 도메인 이름 / 소문자로 반환한다") {
    // When
    val host = HttpTargets.host("API.OpenAI.com")

    // Then
    assertEquals(host, "api.openai.com")
  }

  test("host / IP, 단일 이름, 포트, 경로, 내부 도메인 / 거부한다") {
    // Given
    val invalid = Seq("10.0.0.1", "[::1]", "localhost", "api.example.com:8443", "api.example.com/x", "db.service.consul",
      "printer.local", "metadata.google.internal", "-bad.example.com", "")

    // When / Then
    invalid.foreach(host => intercept[ExpectedException](HttpTargets.host(host)))
  }

  test("headers / 관리 헤더, 대소문자 중복, 줄바꿈, 잘못된 참조 / 거부한다") {
    // Given
    val invalid = Seq(
      Map("Host" -> "evil.com"),
      Map("Authorization" -> "a", "authorization" -> "b"),
      Map("X-Key" -> "a\r\nX-Other: b"),
      Map("X-Key" -> "{{secret.KEY}}"),
      Map("X Key" -> "a")
    )

    // When / Then
    invalid.foreach(headers => intercept[ExpectedException](HttpTargets.headers(headers)))
  }

  test("headers / 시크릿 참조가 있는 값 / 그대로 저장한다") {
    // Given
    val headers = Map("Authorization" -> "Bearer {{secrets.API_KEY}}")

    // When
    val valid = HttpTargets.headers(headers)

    // Then
    assertEquals(valid, headers)
  }

  test("query / 11개 이상 / 거부한다") {
    // When / Then
    intercept[ExpectedException](HttpTargets.query((1 to 11).map(i => s"q$i" -> "v").toMap))
  }

  test("requireCapacity / 20개가 찬 상태 / 새 호스트는 거부하고 기존 호스트 수정은 허용한다") {
    // When / Then
    intercept[ExpectedException](HttpTargets.requireCapacity(exists = false, count = 20))
    HttpTargets.requireCapacity(exists = true, count = 20)
  }
