package com.cowork.applet.domain.applet.service

import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.support.Fixtures

class AppletReaderTest extends munit.FunSuite:

  test("requireVisible / 게시되지 않은 앱을 다른 사용자가 조회 / 404로 숨긴다") {
    // Given
    val applet = Fixtures.applet(published = false)

    // When
    val error = intercept[ExpectedException](AppletReader.requireVisible(applet, Fixtures.Other))

    // Then
    assertEquals(error.status, 404)
  }

  test("requireVisible / 게시된 앱을 다른 사용자가 조회 / 허용한다") {
    // Given
    val applet = Fixtures.applet(published = true)

    // When
    val visible = AppletReader.requireVisible(applet, Fixtures.Other)

    // Then
    assertEquals(visible, applet)
  }

  test("requireVisible / 게시되지 않은 앱을 소유자가 조회 / 허용한다") {
    // Given
    val applet = Fixtures.applet(published = false)

    // When
    val visible = AppletReader.requireVisible(applet, Fixtures.Owner)

    // Then
    assertEquals(visible, applet)
  }

  test("requireOwned / 게시된 앱을 다른 사용자가 관리 / 403으로 거부한다") {
    // Given
    val applet = Fixtures.applet(published = true)

    // When
    val error = intercept[ExpectedException](AppletReader.requireOwned(applet, Fixtures.Other))

    // Then
    assertEquals(error.status, 403)
  }

  test("requireOwned / 게시되지 않은 앱을 다른 사용자가 관리 / 존재를 드러내지 않고 404로 거부한다") {
    // Given
    val applet = Fixtures.applet(published = false)

    // When
    val error = intercept[ExpectedException](AppletReader.requireOwned(applet, Fixtures.Other))

    // Then
    assertEquals(error.status, 404)
  }
