package com.cowork.applet.domain.function.service

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.function.service.handler.{HttpFetchRateLimiter, HttpFunctions, StorageFunctions}
import com.cowork.applet.domain.function.service.impl.InvokePlatformFunctionServiceImpl
import com.cowork.applet.domain.storage.model.{StorageEntry, StorageScope}
import com.cowork.applet.domain.storage.service.StorageLimits
import com.cowork.applet.global.crypto.SecretCipher
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.support.{
  FakeOutboundHttpClient,
  Fixtures,
  InMemoryAppletHttpTargetRepository,
  InMemoryAppletRepository,
  InMemoryAppletSecretRepository,
  InMemoryAppletStorageRepository
}
import java.time.Instant
import play.api.libs.json.{JsNull, JsObject, Json}
import scala.concurrent.{ExecutionContext, Future}

class InvokePlatformFunctionServiceImplTest extends munit.FunSuite:

  private given ExecutionContext = munitExecutionContext

  private final class Context(published: Boolean = true):
    val storage = InMemoryAppletStorageRepository()
    val httpFunctions = HttpFunctions(
      InMemoryAppletHttpTargetRepository(),
      InMemoryAppletSecretRepository(),
      SecretCipher(Array.fill[Byte](32)(1)),
      FakeOutboundHttpClient(_ => Future.failed(AssertionError("Storage tests must not call the network."))),
      HttpFetchRateLimiter()
    )
    val registry = PlatformFunctionRegistry(StorageFunctions(storage), httpFunctions)
    val service = InvokePlatformFunctionServiceImpl(AppletReader(InMemoryAppletRepository(Fixtures.applet(published))), registry)

    def call(userId: String, name: String, arguments: JsObject) = service.execute(userId, "applet-1", name, arguments)

  private def status(result: Future[?]): Future[Int] = result.failed.map(_.asInstanceOf[ExpectedException].status)

  test("catalog / 등록된 함수 / 이름과 인자 정의를 반환한다") {
    // Given
    val context = Context()

    // When
    val names = context.registry.catalog.map(_.name)

    // Then
    assertEquals(names, Seq("storage.get", "storage.set", "storage.delete", "storage.list", "http.fetch"))
  }

  test("storage.set 후 storage.get / USER 범위 / 저장한 사용자만 값을 읽는다") {
    // Given
    val context = Context()

    // When
    for
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "score", "value" -> Json.obj("best" -> 10)))
      mine <- context.call(Fixtures.Owner, "storage.get", Json.obj("key" -> "score"))
      others <- context.call(Fixtures.Other, "storage.get", Json.obj("key" -> "score"))
    yield
      // Then
      assertEquals(mine, Json.obj("value" -> Json.obj("best" -> 10)))
      assertEquals(others, Json.obj("value" -> JsNull))
  }

  test("storage.get / APPLET 범위 / 다른 사용자가 저장한 공유 값을 읽는다") {
    // Given
    val context = Context()

    // When
    for
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "room", "value" -> "open", "scope" -> "APPLET"))
      shared <- context.call(Fixtures.Other, "storage.get", Json.obj("key" -> "room", "scope" -> "APPLET"))
    yield
      // Then
      assertEquals(shared, Json.obj("value" -> "open"))
  }

  test("storage.list / 접두사와 개수 / 키 순서로 잘라 반환한다") {
    // Given
    val context = Context()
    val writes = Seq("b:2", "a:1", "b:1", "b:3").map(key => context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> key, "value" -> 1)))

    // When
    Future.sequence(writes).flatMap(_ => context.call(Fixtures.Owner, "storage.list", Json.obj("prefix" -> "b:", "limit" -> 2))).map { result =>
      // Then
      assertEquals((result \ "entries").as[Seq[JsObject]].map(e => (e \ "key").as[String]), Seq("b:1", "b:2"))
    }
  }

  test("storage.delete / 있는 키와 없는 키 / 삭제 여부를 반환한다") {
    // Given
    val context = Context()

    // When
    for
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "k", "value" -> 1))
      first <- context.call(Fixtures.Owner, "storage.delete", Json.obj("key" -> "k"))
      second <- context.call(Fixtures.Owner, "storage.delete", Json.obj("key" -> "k"))
    yield
      // Then
      assertEquals(first, Json.obj("deleted" -> true))
      assertEquals(second, Json.obj("deleted" -> false))
  }

  test("storage.set / 앱 저장소가 가득 참 / 새 키는 거부하고 기존 키 수정은 허용한다") {
    // Given
    val context = Context()
    val now = Instant.now()
    (1 to StorageLimits.MaxEntriesPerApplet).foreach { i =>
      context.storage.entries += StorageEntry("applet-1", StorageScope.USER, s"user-${i % 20}", s"k$i", JsNull, now)
    }

    // When
    for
      rejected <- status(context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "new", "value" -> 1)))
      _ <- context.service.execute("user-1", "applet-1", "storage.set", Json.obj("key" -> "k1", "value" -> 2))
    yield
      // Then
      assertEquals(rejected, 400)
      assertEquals(context.storage.entries.size, StorageLimits.MaxEntriesPerApplet)
  }

  test("storage.set / 한 사용자가 USER 범위 상한을 채움 / 그 사용자의 새 키만 거부한다") {
    // Given
    val context = Context(published = true)
    val now = Instant.now()
    (1 to StorageLimits.MaxEntriesPerUser).foreach { i =>
      context.storage.entries += StorageEntry("applet-1", StorageScope.USER, Fixtures.Other, s"k$i", JsNull, now)
    }

    // When
    for
      rejected <- status(context.call(Fixtures.Other, "storage.set", Json.obj("key" -> "new", "value" -> 1)))
      _ <- context.call(Fixtures.Other, "storage.set", Json.obj("key" -> "k1", "value" -> 2))
      _ <- context.call(Fixtures.Other, "storage.set", Json.obj("key" -> "shared", "value" -> 3, "scope" -> "APPLET"))
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "new", "value" -> 1))
    yield
      // Then
      assertEquals(rejected, 400)
      assertEquals(context.storage.entries.size, StorageLimits.MaxEntriesPerUser + 2)
  }

  test("storage.set / 16KB를 넘는 값, 잘못된 키, 잘못된 범위 / 400으로 거부한다") {
    // Given
    val context = Context()
    val invalid = Seq(
      Json.obj("key" -> "big", "value" -> "a" * (StorageLimits.MaxValueBytes + 1)),
      Json.obj("key" -> "공백 키", "value" -> 1),
      Json.obj("key" -> "k", "value" -> 1, "scope" -> "GLOBAL"),
      Json.obj("value" -> 1)
    )

    // When
    Future.sequence(invalid.map(arguments => status(context.call(Fixtures.Owner, "storage.set", arguments)))).map { statuses =>
      // Then
      assertEquals(statuses, Seq(400, 400, 400, 400))
      assertEquals(context.storage.entries.size, 0)
    }
  }

  test("execute / 없는 함수 이름 / 404로 거부한다") {
    // Given
    val context = Context()

    // When
    status(context.call(Fixtures.Owner, "shell.exec", Json.obj())).map { code =>
      // Then
      assertEquals(code, 404)
    }
  }

  test("execute / 게시되지 않은 앱을 다른 사용자가 호출 / 404로 거부한다") {
    // Given
    val context = Context(published = false)

    // When
    status(context.call(Fixtures.Other, "storage.get", Json.obj("key" -> "k"))).map { code =>
      // Then
      assertEquals(code, 404)
    }
  }
