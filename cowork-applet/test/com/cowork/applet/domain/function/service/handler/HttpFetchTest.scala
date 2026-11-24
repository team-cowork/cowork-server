package com.cowork.applet.domain.function.service.handler

import com.cowork.applet.domain.function.model.FunctionArguments
import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.global.outbound.OutboundResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.time.Instant
import play.api.libs.json.{JsObject, Json}

class HttpFetchTest extends munit.FunSuite:

  private def parse(arguments: JsObject): HttpFetchCall = HttpFetch.parse(FunctionArguments(arguments))

  private def target(headers: Map[String, String] = Map.empty, query: Map[String, String] = Map.empty) =
    AppletHttpTarget("applet-1", "api.example.com", headers, query, Instant.EPOCH)

  test("parse / https URL / 호스트를 소문자로 읽고 프래그먼트를 버린다") {
    // When
    val call = parse(Json.obj("url" -> "https://API.example.com/v1/items?a=1#top"))

    // Then
    assertEquals(call.host, "api.example.com")
    assertEquals(call.url.toString, "https://api.example.com/v1/items?a=1")
    assertEquals(call.method, "GET")
  }

  test("parse / http, 다른 포트, 사용자 정보, 잘못된 URL / 거부한다") {
    // Given
    val invalid = Seq("http://api.example.com", "https://api.example.com:8443/", "https://user:pw@api.example.com/", "not a url")

    // When / Then
    invalid.foreach(url => intercept[ExpectedException](parse(Json.obj("url" -> url))))
  }

  test("parse / 지원하지 않는 메서드, GET 본문, 관리 헤더, 큰 본문 / 거부한다") {
    // Given
    val url = "https://api.example.com/"
    val invalid = Seq(
      Json.obj("url" -> url, "method" -> "CONNECT"),
      Json.obj("url" -> url, "body" -> "x"),
      Json.obj("url" -> url, "headers" -> Json.obj("Host" -> "evil.com")),
      Json.obj("url" -> url, "headers" -> Json.obj("Accept-Encoding" -> "gzip")),
      Json.obj("url" -> url, "headers" -> Json.obj("Range" -> "bytes=0-3")),
      Json.obj("url" -> url, "headers" -> Json.obj("X-A" -> "a\nb")),
      Json.obj("url" -> url, "method" -> "POST", "body" -> "a" * (HttpFetch.MaxBodyBytes + 1))
    )

    // When / Then
    invalid.foreach(arguments => intercept[ExpectedException](parse(arguments)))
  }

  test("build / 시크릿 헤더와 쿼리 / 서버가 값을 넣는다") {
    // Given
    val call = parse(Json.obj("url" -> "https://api.example.com/v1?a=1", "method" -> "post", "headers" -> Json.obj("X-Trace" -> "t")))
    val configured = target(Map("Authorization" -> "Bearer {{secrets.TOKEN}}"), Map("key" -> "{{secrets.KEY}}"))

    // When
    val request = HttpFetch.build(call, configured, Map("TOKEN" -> "tok-123", "KEY" -> "k&v"))

    // Then
    assertEquals(request.method, "POST")
    assertEquals(request.url, "https://api.example.com/v1?a=1&key=k%26v")
    assertEquals(request.headers, Seq("X-Trace" -> "t", "Authorization" -> "Bearer tok-123"))
    assertEquals(request.body.map(_.length), Some(0))
  }

  test("build / 대상이 정한 헤더나 쿼리를 호출자가 보냄 / 거부한다") {
    // Given
    val configured = target(Map("Authorization" -> "{{secrets.TOKEN}}"), Map("key" -> "{{secrets.KEY}}"))
    val secrets = Map("TOKEN" -> "t", "KEY" -> "k")
    val header = parse(Json.obj("url" -> "https://api.example.com/", "headers" -> Json.obj("authorization" -> "mine")))
    val query = parse(Json.obj("url" -> "https://api.example.com/?key=mine"))

    // When / Then
    intercept[ExpectedException](HttpFetch.build(header, configured, secrets))
    intercept[ExpectedException](HttpFetch.build(query, configured, secrets))
  }

  test("build / 줄바꿈이 있는 시크릿을 헤더에 넣음 / 거부한다") {
    // Given
    val call = parse(Json.obj("url" -> "https://api.example.com/"))

    // When / Then
    intercept[ExpectedException](
      HttpFetch.build(call, target(Map("X-Key" -> "{{secrets.KEY}}")), Map("KEY" -> "a\r\nX-Evil: 1"))
    )
  }

  test("result / 시크릿을 되돌려 준 응답 / 본문과 헤더의 시크릿을 가리고 쿠키 헤더를 버린다") {
    // Given
    val response = OutboundResponse(
      200,
      Seq("Content-Type" -> "application/json", "Set-Cookie" -> "s=1", "X-Echo" -> "Bearer tok-123"),
      Some("application/json"),
      """{"auth":"Bearer tok-123"}""".getBytes(UTF_8)
    )

    // When
    val result = HttpFetch.result(response, Seq("tok-123"))

    // Then
    assertEquals((result \ "body").as[String], """{"auth":"Bearer [REDACTED]"}""")
    assertEquals((result \ "headers" \ "x-echo").as[String], "Bearer [REDACTED]")
    assert((result \ "headers" \ "set-cookie").isEmpty)
    assertEquals((result \ "bodyEncoding").as[String], "utf8")
  }

  test("result / 텍스트가 아닌 응답 / base64로 반환한다") {
    // Given
    val response = OutboundResponse(200, Nil, Some("image/png"), Array[Byte](1, 2, 3))

    // When
    val result = HttpFetch.result(response, Nil)

    // Then
    assertEquals((result \ "body").as[String], "AQID")
    assertEquals((result \ "bodyEncoding").as[String], "base64")
  }

  test("result / 텍스트가 아닌 응답에 시크릿이 있음 / base64로 바꾸기 전에 가린다") {
    // Given
    val response = OutboundResponse(200, Nil, None, "echo tok-123 end".getBytes(UTF_8))

    // When
    val result = HttpFetch.result(response, Seq("tok-123"))

    // Then
    val decoded = String(Base64.getDecoder.decode((result \ "body").as[String]), UTF_8)
    assertEquals((result \ "bodyEncoding").as[String], "base64")
    assertEquals(decoded, "echo [REDACTED] end")
  }

  test("result / 쿼리 인코딩된 형태로 되돌아온 시크릿 / 인코딩된 형태도 가린다") {
    // Given
    val call = parse(Json.obj("url" -> "https://api.example.com/v1"))
    val request = HttpFetch.build(call, target(query = Map("key" -> "{{secrets.KEY}}")), Map("KEY" -> "tok+a/b="))
    val echoed = s"""{"url":"${request.url}"}"""
    val response = OutboundResponse(200, Seq("Location" -> request.url), Some("application/json"), echoed.getBytes(UTF_8))

    // When
    val result = HttpFetch.result(response, Seq("tok+a/b="))

    // Then
    assertEquals((result \ "body").as[String], """{"url":"https://api.example.com/v1?key=[REDACTED]"}""")
    assertEquals((result \ "headers" \ "location").as[String], "https://api.example.com/v1?key=[REDACTED]")
  }

  test("result / OkHttp가 풀지 않은 압축 응답 / 가림을 할 수 없어 Bad Gateway로 거부한다") {
    // Given
    val compressed = OutboundResponse(200, Seq("Content-Encoding" -> "br"), Some("application/json"), Array[Byte](1, 2))
    val identity = OutboundResponse(200, Seq("Content-Encoding" -> "identity"), Some("text/plain"), "ok".getBytes(UTF_8))

    // When
    val error = intercept[ExpectedException](HttpFetch.result(compressed, Nil))

    // Then
    assertEquals(error.status, 502)
    assertEquals((HttpFetch.result(identity, Nil) \ "body").as[String], "ok")
  }
