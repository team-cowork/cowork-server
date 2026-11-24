package com.cowork.applet.domain.network.service

import com.cowork.applet.global.error.ExpectedException

class SecretTemplatesTest extends munit.FunSuite:

  test("references / 여러 참조와 공백 / 이름 집합을 반환한다") {
    // When
    val names = SecretTemplates.references("{{secrets.A}}:{{ secrets.B_2 }}:{{secrets.A}}")

    // Then
    assertEquals(names, Set("A", "B_2"))
  }

  test("render / 등록된 시크릿 / 값으로 바꾸고 정규식 특수 문자를 그대로 넣는다") {
    // Given
    val secrets = Map("KEY" -> "a$1\\b")

    // When
    val rendered = SecretTemplates.render("Bearer {{secrets.KEY}}", secrets)

    // Then
    assertEquals(rendered, "Bearer a$1\\b")
  }

  test("render / 등록되지 않은 시크릿 / 거부한다") {
    // When
    val error = intercept[ExpectedException](SecretTemplates.render("{{secrets.MISSING}}", Map.empty))

    // Then
    assertEquals(error.status, 400)
  }

  test("validate / 형식이 틀린 참조 / 거부한다") {
    // Given
    val invalid = Seq("{{secrets.lower}}", "{{env.KEY}}", "{{secrets.KEY}", "plain {{")

    // When / Then
    invalid.foreach(template => intercept[ExpectedException](SecretTemplates.validate(template)))
  }
