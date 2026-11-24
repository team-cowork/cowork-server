package com.cowork.applet.domain.environment.service

import com.cowork.applet.global.error.ExpectedException

class EnvironmentEntriesTest extends munit.FunSuite:

  test("name / 대문자, 숫자, 밑줄로 된 이름 / 그대로 반환한다") {
    // When
    val name = EnvironmentEntries.name("API_BASE_URL_2")

    // Then
    assertEquals(name, "API_BASE_URL_2")
  }

  test("name / 소문자, 숫자 시작, 하이픈, 빈 값, 65자 / 거부한다") {
    // Given
    val invalid = Seq("api_key", "1KEY", "API-KEY", "", "A" * 65)

    // When / Then
    invalid.foreach(name => intercept[ExpectedException](EnvironmentEntries.name(name)))
  }

  test("name / COWORK_ 접두사 / 플랫폼 예약어로 거부한다") {
    // When
    val error = intercept[ExpectedException](EnvironmentEntries.name("COWORK_APPLET_ID"))

    // Then
    assertEquals(error.status, 400)
  }

  test("value / 4096자를 넘는 값 / 거부한다") {
    // When / Then
    intercept[ExpectedException](EnvironmentEntries.value("a" * 4097))
    assertEquals(EnvironmentEntries.value("a" * 4096).length, 4096)
  }

  test("requireCapacity / 50개가 찬 상태 / 새 이름은 거부하고 기존 이름 수정은 허용한다") {
    // When / Then
    intercept[ExpectedException](EnvironmentEntries.requireCapacity(exists = false, count = 50))
    EnvironmentEntries.requireCapacity(exists = true, count = 50)
    EnvironmentEntries.requireCapacity(exists = false, count = 49)
  }
