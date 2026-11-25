package com.cowork.applet.domain.environment.service

import com.cowork.applet.global.error.ExpectedException
import play.api.http.Status.BAD_REQUEST

object EnvironmentEntries:
  val MaxEntries = 50
  val MaxValueLength = 4096
  // The platform adds its own runtime values under this prefix.
  val ReservedPrefix = "COWORK_"

  private val NamePattern = """[A-Z][A-Z0-9_]{0,63}""".r

  def name(value: String): String =
    if !NamePattern.matches(value) then
      throw ExpectedException(BAD_REQUEST, "이름은 대문자로 시작하고 대문자, 숫자, 밑줄만 사용하여 64자 이하로 입력하십시오.")
    if value.startsWith(ReservedPrefix) then
      throw ExpectedException(BAD_REQUEST, "COWORK_로 시작하는 이름은 플랫폼이 사용합니다.")
    value

  def value(content: String): String =
    if content.length > MaxValueLength then throw ExpectedException(BAD_REQUEST, "값은 4096자 이하로 입력하십시오.")
    content

  def requireCapacity(exists: Boolean, count: Int): Unit =
    if !exists && count >= MaxEntries then
      throw ExpectedException(BAD_REQUEST, "앱 하나에 최대 50개까지 등록할 수 있습니다.")
