package com.cowork.applet.domain.network.service

import com.cowork.applet.global.error.ExpectedException
import play.api.http.Status.BAD_REQUEST

object HttpTargets:
  val MaxTargets = 20
  val MaxEntries = 10
  val MaxValueLength = 2048

  // Labels follow DNS rules and the last label starts with a letter, so IP literals and single-label names fail.
  private val HostPattern =
    """(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?""".r
  private val InternalSuffixes = Seq(".local", ".localhost", ".internal", ".consul", ".lan", ".home.arpa", ".localdomain")
  private val HeaderNamePattern = """[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}""".r

  // OkHttp manages these headers, and proxy headers could be used against intermediaries.
  val ForbiddenHeaders: Set[String] = Set(
    "host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "keep-alive",
    "expect", "proxy-authorization", "proxy-connection"
  )

  def host(value: String): String =
    val lower = value.toLowerCase
    if !HostPattern.matches(lower) || InternalSuffixes.exists(suffix => lower.endsWith(suffix)) then
      throw ExpectedException(BAD_REQUEST, "공개 인터넷 도메인 이름만 HTTP 대상으로 등록할 수 있습니다.")
    lower

  def headers(values: Map[String, String]): Map[String, String] =
    requireSize(values)
    if values.keySet.map(_.toLowerCase).size != values.size then
      throw ExpectedException(BAD_REQUEST, "대소문자만 다른 헤더 이름을 함께 설정할 수 없습니다.")
    values.map { (name, value) =>
      if !isHeaderName(name) then throw ExpectedException(BAD_REQUEST, "헤더 이름이 올바르지 않습니다.")
      name -> templateValue(value)
    }

  def query(values: Map[String, String]): Map[String, String] =
    requireSize(values)
    values.map { (name, value) =>
      if name.isEmpty || name.length > 128 then throw ExpectedException(BAD_REQUEST, "쿼리 이름이 올바르지 않습니다.")
      name -> templateValue(value)
    }

  def isHeaderName(name: String): Boolean =
    HeaderNamePattern.matches(name) && !ForbiddenHeaders.contains(name.toLowerCase)

  def hasLineBreak(value: String): Boolean = value.exists(c => c == '\r' || c == '\n')

  def requireCapacity(exists: Boolean, count: Int): Unit =
    if !exists && count >= MaxTargets then
      throw ExpectedException(BAD_REQUEST, "HTTP 대상은 앱 하나에 최대 20개까지 등록할 수 있습니다.")

  private def requireSize(values: Map[String, String]): Unit =
    if values.size > MaxEntries then throw ExpectedException(BAD_REQUEST, "헤더와 쿼리는 각각 최대 10개까지 설정할 수 있습니다.")

  private def templateValue(value: String): String =
    if value.length > MaxValueLength || hasLineBreak(value) then
      throw ExpectedException(BAD_REQUEST, "설정 값은 줄바꿈 없이 2048자 이하로 입력하십시오.")
    SecretTemplates.validate(value)
