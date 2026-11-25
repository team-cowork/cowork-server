package com.cowork.applet.domain.network.service

import com.cowork.applet.global.error.ExpectedException
import java.util.regex.Matcher
import play.api.http.Status.BAD_REQUEST

object SecretTemplates:
  private val Reference = """\{\{\s*secrets\.([A-Z][A-Z0-9_]{0,63})\s*\}\}""".r

  def validate(template: String): String =
    if Reference.replaceAllIn(template, "").contains("{{") then
      throw ExpectedException(BAD_REQUEST, "시크릿 참조는 {{secrets.NAME}} 형식으로 입력하십시오.")
    template

  def references(template: String): Set[String] = Reference.findAllMatchIn(template).map(_.group(1)).toSet

  def render(template: String, secrets: Map[String, String]): String =
    Reference.replaceAllIn(
      template,
      found =>
        val value = secrets.getOrElse(
          found.group(1),
          throw ExpectedException(BAD_REQUEST, "HTTP 대상 설정에서 참조한 시크릿이 등록되어 있지 않습니다.")
        )
        Matcher.quoteReplacement(value)
    )
