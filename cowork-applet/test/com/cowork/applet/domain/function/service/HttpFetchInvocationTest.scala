package com.cowork.applet.domain.function.service

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.function.service.handler.{HttpFetchRateLimiter, HttpFunctions, StorageFunctions}
import com.cowork.applet.domain.function.service.impl.InvokePlatformFunctionServiceImpl
import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.global.crypto.SecretCipher
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.global.outbound.{BlockedAddressException, OutboundRequest, OutboundResponse}
import com.cowork.applet.support.{
  FakeOutboundHttpClient,
  Fixtures,
  InMemoryAppletHttpTargetRepository,
  InMemoryAppletRepository,
  InMemoryAppletSecretRepository,
  InMemoryAppletStorageRepository
}
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import play.api.libs.json.Json
import scala.concurrent.{ExecutionContext, Future}

class HttpFetchInvocationTest extends munit.FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val cipher = SecretCipher(Array.fill[Byte](32)(3))
  private val okResponse = OutboundResponse(200, Nil, Some("application/json"), """{"ok":true}""".getBytes(UTF_8))

  private final class Context(answer: OutboundRequest => Future[OutboundResponse] = _ => Future.successful(okResponse)):
    val client = FakeOutboundHttpClient(answer)
    val secrets = InMemoryAppletSecretRepository()
    secrets.secrets += AppletSecret(
      "applet-1",
      "TOKEN",
      cipher.encrypt("tok-secret", AppletSecret.associatedData("applet-1", "TOKEN")),
      Instant.EPOCH
    )
    val targets = InMemoryAppletHttpTargetRepository(
      AppletHttpTarget("applet-1", "api.example.com", Map("Authorization" -> "Bearer {{secrets.TOKEN}}"), Map.empty, Instant.EPOCH)
    )
    val rateLimiter = HttpFetchRateLimiter()
    val registry = PlatformFunctionRegistry(
      StorageFunctions(InMemoryAppletStorageRepository()),
      HttpFunctions(targets, secrets, cipher, client, rateLimiter)
    )
    val service =
      InvokePlatformFunctionServiceImpl(AppletReader(InMemoryAppletRepository(Fixtures.applet(published = true))), registry)

    def fetch(url: String) = service.execute(Fixtures.Other, "applet-1", "http.fetch", Json.obj("url" -> url))

  private def status(result: Future[?]): Future[Int] = result.failed.map(_.asInstanceOf[ExpectedException].status)

  test("http.fetch / 등록된 호스트 / 복호화한 시크릿을 넣어 요청하고 응답을 반환한다") {
    // Given
    val context = Context()

    // When
    context.fetch("https://api.example.com/v1/me").map { result =>
      // Then
      assertEquals(context.client.requests.head.headers, Seq("Authorization" -> "Bearer tok-secret"))
      assertEquals((result \ "status").as[Int], 200)
      assertEquals((result \ "body").as[String], """{"ok":true}""")
    }
  }

  test("http.fetch / 등록되지 않은 호스트 / 요청하지 않고 403으로 거부한다") {
    // Given
    val context = Context()

    // When
    status(context.fetch("https://evil.example.org/")).map { code =>
      // Then
      assertEquals(code, 403)
      assertEquals(context.client.requests.size, 0)
    }
  }

  test("http.fetch / 공개되지 않은 주소로 해석되는 호스트 / 403으로 거부한다") {
    // Given
    val context = Context(_ => Future.failed(BlockedAddressException("api.example.com")))

    // When
    status(context.fetch("https://api.example.com/")).map { code =>
      // Then
      assertEquals(code, 403)
    }
  }

  test("http.fetch / 연결 실패와 시간 초과 / 각각 502와 504로 바꾼다") {
    // Given
    val refused = Context(_ => Future.failed(IOException("refused")))
    val slow = Context(_ => Future.failed(SocketTimeoutException("timeout")))

    // When
    for
      refusedCode <- status(refused.fetch("https://api.example.com/"))
      slowCode <- status(slow.fetch("https://api.example.com/"))
    yield
      // Then
      assertEquals(refusedCode, 502)
      assertEquals(slowCode, 504)
  }

  test("http.fetch / 앱의 분당 한도를 넘음 / 429로 거부한다") {
    // Given
    val context = Context()
    (1 to HttpFetchRateLimiter.AppletLimit).foreach(i => context.rateLimiter.tryAcquire("applet-1", s"user-$i"))

    // When
    status(context.fetch("https://api.example.com/")).map { code =>
      // Then
      assertEquals(code, 429)
      assertEquals(context.client.requests.size, 0)
    }
  }

  test("http.fetch / 한 사용자가 자기 한도를 모두 씀 / 그 사용자만 거부하고 다른 사용자는 허용한다") {
    // Given
    val context = Context()
    (1 to HttpFetchRateLimiter.UserLimit).foreach(_ => context.rateLimiter.tryAcquire("applet-1", Fixtures.Other))

    // When
    for
      blocked <- status(context.fetch("https://api.example.com/"))
      allowed <- context.service.execute(Fixtures.Owner, "applet-1", "http.fetch", Json.obj("url" -> "https://api.example.com/"))
    yield
      // Then
      assertEquals(blocked, 429)
      assertEquals((allowed \ "status").as[Int], 200)
  }
