package com.cowork.applet.global.crypto

import javax.crypto.AEADBadTagException

class SecretCipherTest extends munit.FunSuite:

  private val cipher = SecretCipher(Array.fill[Byte](32)(7))

  test("encrypt / 같은 값을 두 번 암호화 / 서로 다른 암호문이 복호화하면 원문이 된다") {
    // Given
    val plaintext = "sk-live-한글-123"

    // When
    val first = cipher.encrypt(plaintext, "applet-secret:a:KEY")
    val second = cipher.encrypt(plaintext, "applet-secret:a:KEY")

    // Then
    assertNotEquals(first, second)
    assert(!first.contains(plaintext))
    assertEquals(cipher.decrypt(first, "applet-secret:a:KEY"), plaintext)
    assertEquals(cipher.decrypt(second, "applet-secret:a:KEY"), plaintext)
  }

  test("decrypt / 다른 레코드의 연관 데이터 / 복호화하지 않는다") {
    // Given
    val encrypted = cipher.encrypt("value", "applet-secret:a:KEY")

    // When / Then
    intercept[AEADBadTagException](cipher.decrypt(encrypted, "applet-secret:b:KEY"))
  }

  test("decrypt / 다른 키 / 복호화하지 않는다") {
    // Given
    val encrypted = cipher.encrypt("value", "aad")
    val other = SecretCipher(Array.fill[Byte](32)(8))

    // When / Then
    intercept[AEADBadTagException](other.decrypt(encrypted, "aad"))
  }

  test("decrypt / 구분자가 없는 값 / 형식 오류로 거부한다") {
    // When / Then
    intercept[IllegalArgumentException](cipher.decrypt("not-encrypted", "aad"))
  }

  test("constructor / 32바이트가 아닌 키 / 생성하지 않는다") {
    // When / Then
    intercept[IllegalArgumentException](SecretCipher(Array.fill[Byte](16)(1)))
  }
