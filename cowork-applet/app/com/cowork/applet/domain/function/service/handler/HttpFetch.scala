package com.cowork.applet.domain.function.service.handler

import com.cowork.applet.domain.function.model.FunctionArguments
import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.service.{HttpTargets, SecretTemplates}
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.global.outbound.{OutboundRequest, OutboundResponse}
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import okhttp3.HttpUrl
import play.api.http.Status.BAD_REQUEST
import play.api.libs.json.{JsObject, Json}

final case class HttpFetchCall(url: HttpUrl, method: String, headers: Map[String, String], body: Option[Array[Byte]]):
  def host: String = url.host()

object HttpFetch:
  val Methods: Set[String] = Set("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
  val MaxHeaders = 20
  val MaxHeaderValueLength = 8192
  val MaxBodyBytes: Int = 256 * 1024

  private val DroppedResponseHeaders = Set("set-cookie", "set-cookie2")
  private val TextualTypes = Seq("json", "xml", "javascript", "x-www-form-urlencoded")
  private val Redacted = "[REDACTED]"
  private val RedactedBytes = Redacted.getBytes(UTF_8)
  // Shorter values would also replace ordinary text in responses.
  private val MinRedactedLength = 4

  def parse(arguments: FunctionArguments): HttpFetchCall =
    val url = Option(HttpUrl.parse(arguments.required[String]("url")))
      .filter(u => u.scheme() == "https" && u.port() == 443 && u.username().isEmpty && u.password().isEmpty)
      .getOrElse(throw ExpectedException(BAD_REQUEST, "URL은 기본 포트를 쓰는 https 주소로 입력하십시오."))
    val method = arguments.optional[String]("method").map(_.toUpperCase).getOrElse("GET")
    if !Methods.contains(method) then throw ExpectedException(BAD_REQUEST, "지원하지 않는 HTTP 메서드입니다.")
    val headers = arguments.optional[Map[String, String]]("headers").getOrElse(Map.empty)
    if headers.size > MaxHeaders then throw ExpectedException(BAD_REQUEST, "헤더는 최대 20개까지 보낼 수 있습니다.")
    headers.foreach { (name, value) =>
      if !HttpTargets.isHeaderName(name) || HttpTargets.hasLineBreak(value) || value.length > MaxHeaderValueLength then
        throw ExpectedException(BAD_REQUEST, "보낼 수 없는 헤더가 있습니다.")
    }
    val body = arguments.optional[String]("body").map(_.getBytes(UTF_8))
    if body.exists(_.length > MaxBodyBytes) then throw ExpectedException(BAD_REQUEST, "요청 본문은 256KB를 넘을 수 없습니다.")
    if body.isDefined && (method == "GET" || method == "HEAD") then
      throw ExpectedException(BAD_REQUEST, "GET과 HEAD 요청에는 본문을 보낼 수 없습니다.")
    HttpFetchCall(url.newBuilder().fragment(null).build(), method, headers, body)

  def secretNames(target: AppletHttpTarget): Set[String] =
    (target.headers.values ++ target.query.values).flatMap(SecretTemplates.references).toSet

  // A caller value for a header or query name that the target sets is rejected, so the owner's values always apply.
  def build(call: HttpFetchCall, target: AppletHttpTarget, secrets: Map[String, String]): OutboundRequest =
    val targetHeaders = target.headers.keySet.map(_.toLowerCase)
    if call.headers.keys.exists(name => targetHeaders.contains(name.toLowerCase)) ||
      target.query.keys.exists(name => call.url.queryParameter(name) != null)
    then throw ExpectedException(BAD_REQUEST, "HTTP 대상 설정이 정한 헤더와 쿼리는 바꿀 수 없습니다.")

    val url = target.query.toSeq.sortBy(_._1).foldLeft(call.url.newBuilder()) { case (builder, (name, template)) =>
      builder.addQueryParameter(name, SecretTemplates.render(template, secrets))
    }
    val injected = target.headers.toSeq.sortBy(_._1).map { (name, template) =>
      val value = SecretTemplates.render(template, secrets)
      if HttpTargets.hasLineBreak(value) then
        throw ExpectedException(BAD_REQUEST, "줄바꿈이 있는 시크릿은 헤더에 넣을 수 없습니다.")
      name -> value
    }
    val body = call.body.orElse(Option.when(Set("POST", "PUT", "PATCH").contains(call.method))(Array.emptyByteArray))
    OutboundRequest(call.method, url.build().toString, call.headers.toSeq.sortBy(_._1) ++ injected, body)

  // Redaction keeps an external service that echoes the request from returning a secret to the client.
  // It runs on the raw bytes, so binary responses are covered before base64 encoding, and it also matches
  // the percent-encoded form that query parameters carry.
  def result(response: OutboundResponse, secretValues: Iterable[String]): JsObject =
    val targets = redactionTargets(secretValues)
    def redact(text: String): String = targets.foldLeft(text)((current, secret) => current.replace(secret, Redacted))
    val headers = response.headers.collect {
      case (name, value) if !DroppedResponseHeaders.contains(name.toLowerCase) => name.toLowerCase -> redact(value)
    }
    val bytes = targets.foldLeft(response.body)((current, secret) => replaceAll(current, secret.getBytes(UTF_8), RedactedBytes))
    val (body, encoding) =
      if isTextual(response.contentType) then String(bytes, UTF_8) -> "utf8"
      else Base64.getEncoder.encodeToString(bytes) -> "base64"
    Json.obj(
      "status" -> response.status,
      "headers" -> JsObject(headers.map((name, value) => name -> Json.toJson(value))),
      "body" -> body,
      "bodyEncoding" -> encoding
    )

  private def redactionTargets(secretValues: Iterable[String]): Seq[String] =
    secretValues
      .filter(_.length >= MinRedactedLength)
      .flatMap(secret => Seq(secret, queryEncoded(secret)))
      .toSeq
      .distinct
      .sortBy(-_.length)

  // The same encoding that build uses for query parameters.
  private def queryEncoded(value: String): String =
    HttpUrl.parse("https://redaction.invalid/").newBuilder().addQueryParameter("v", value).build().encodedQuery().stripPrefix("v=")

  private def replaceAll(bytes: Array[Byte], target: Array[Byte], replacement: Array[Byte]): Array[Byte] =
    var index = bytes.indexOfSlice(target)
    if index < 0 then bytes
    else
      val out = ByteArrayOutputStream(bytes.length)
      var from = 0
      while index >= 0 do
        out.write(bytes, from, index - from)
        out.write(replacement)
        from = index + target.length
        index = bytes.indexOfSlice(target, from)
      out.write(bytes, from, bytes.length - from)
      out.toByteArray

  private def isTextual(contentType: Option[String]): Boolean =
    contentType.map(_.toLowerCase).exists(value => value.startsWith("text/") || TextualTypes.exists(value.contains))
