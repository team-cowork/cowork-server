package com.cowork.applet.domain.function.service

import com.cowork.applet.domain.function.model.{FunctionArguments, InvocationContext, PlatformFunction}
import com.cowork.applet.domain.function.service.handler.{HttpFetchRateLimiter, HttpFunctions, PlatformFunctionHandler, StorageFunctions}
import com.cowork.applet.domain.storage.model.{StorageEntry, StorageScope}
import com.cowork.applet.domain.storage.service.StorageLimits
import com.cowork.applet.global.crypto.SecretCipher
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.support.{
  AppletRuntime,
  FakeOutboundHttpClient,
  Fixtures,
  InMemoryAppletHttpTargetRepository,
  InMemoryAppletSecretRepository,
  InMemoryAppletStorageRepository
}
import java.time.Instant
import play.api.libs.json.{JsNull, JsObject, JsString, JsValue, Json}
import scala.concurrent.{ExecutionContext, Future}

class InvokePlatformFunctionServiceImplTest extends munit.FunSuite:

  private given ExecutionContext = munitExecutionContext

  private final class Context(published: Boolean = true, installedIn: Seq[Long] = Seq(Fixtures.TeamA, Fixtures.NoUseTeam)):
    val storage = InMemoryAppletStorageRepository()
    val httpFunctions = HttpFunctions(
      InMemoryAppletHttpTargetRepository(),
      InMemoryAppletSecretRepository(),
      SecretCipher(Array.fill[Byte](32)(1)),
      FakeOutboundHttpClient(_ => Future.failed(AssertionError("Storage tests must not call the network."))),
      HttpFetchRateLimiter()
    )
    val registry = PlatformFunctionRegistry(StorageFunctions(storage), httpFunctions)
    val runtime = AppletRuntime(registry, installedIn, published)
    val service = runtime.invoke

    def call(userId: String, name: String, arguments: JsObject, teamId: Long = Fixtures.TeamA) =
      service.execute(userId, teamId, "applet-1", name, arguments)

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
    (1 to StorageLimits.MaxEntriesPerTeam).foreach { i =>
      val owner = if i == 1 then Fixtures.Owner else s"user-${i % 20}"
      context.storage.entries += StorageEntry("applet-1", Fixtures.TeamA, StorageScope.USER, owner, s"k$i", JsNull, now)
    }

    // When
    for
      rejected <- status(context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "new", "value" -> 1)))
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "k1", "value" -> 2))
    yield
      // Then
      assertEquals(rejected, 400)
      assertEquals(context.storage.entries.size, StorageLimits.MaxEntriesPerTeam)
  }

  test("storage.set / 한 사용자가 USER 범위 상한을 채움 / 그 사용자의 새 키만 거부한다") {
    // Given
    val context = Context(published = true)
    val now = Instant.now()
    (1 to StorageLimits.MaxEntriesPerUser).foreach { i =>
      context.storage.entries += StorageEntry("applet-1", Fixtures.TeamA, StorageScope.USER, Fixtures.Other, s"k$i", JsNull, now)
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

  test("execute / 설치한 팀에서 게시가 취소된 앱 / 404로 거부한다") {
    // Given
    val context = Context(published = false)

    // When
    status(context.call(Fixtures.Owner, "storage.get", Json.obj("key" -> "k"))).map { code =>
      // Then
      assertEquals(code, 404)
    }
  }

  test("execute / 설치하지 않은 팀의 멤버와 소유자 / 404로 거부한다") {
    // Given
    val context = Context()

    // When
    val calls = Seq(Fixtures.Owner, Fixtures.Other).map(user =>
      status(context.call(user, "storage.get", Json.obj("key" -> "k"), teamId = Fixtures.TeamB))
    )

    // Then
    Future.sequence(calls).map(codes => assertEquals(codes, Seq(404, 404)))
  }

  test("execute / 앱 사용 권한이 없는 멤버와 팀 밖 사용자 / 403으로 거부한다") {
    // Given
    val context = Context()

    // When
    for
      noUse <- status(context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "k", "value" -> 1), teamId = Fixtures.NoUseTeam))
      outsider <- status(context.call("9", "storage.get", Json.obj("key" -> "k")))
    yield
      // Then
      assertEquals(Seq(noUse, outsider), Seq(403, 403))
      assert(context.storage.entries.isEmpty)
  }

  test("storage / 두 팀에 설치된 같은 앱 / 다른 팀의 APPLET과 USER 값을 읽거나 바꾸지 못한다") {
    // Given
    val context = Context(installedIn = Seq(Fixtures.TeamA, Fixtures.TeamB))
    def get(teamId: Long, scope: String) = context.call(Fixtures.Owner, "storage.get", Json.obj("key" -> "k", "scope" -> scope), teamId)

    // When
    for
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "k", "value" -> "a", "scope" -> "APPLET"))
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "k", "value" -> "a"))
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "k", "value" -> "b", "scope" -> "APPLET"), Fixtures.TeamB)
      deleted <- context.call(Fixtures.Owner, "storage.delete", Json.obj("key" -> "k"), Fixtures.TeamB)
      sharedA <- get(Fixtures.TeamA, "APPLET")
      sharedB <- get(Fixtures.TeamB, "APPLET")
      userA <- get(Fixtures.TeamA, "USER")
      userB <- get(Fixtures.TeamB, "USER")
    yield
      // Then
      assertEquals(deleted, Json.obj("deleted" -> false))
      val expected = Seq[JsValue](JsString("a"), JsString("b"), JsString("a"), JsNull).map(v => Json.obj("value" -> v))
      assertEquals(Seq(sharedA, sharedB, userA, userB), expected)
  }

  test("storage.set / 한 팀이 저장소 상한을 채움 / 다른 팀의 새 키는 허용한다") {
    // Given
    val context = Context(installedIn = Seq(Fixtures.TeamA, Fixtures.TeamB))
    val now = Instant.now()
    (1 to StorageLimits.MaxEntriesPerTeam).foreach { i =>
      context.storage.entries += StorageEntry("applet-1", Fixtures.TeamA, StorageScope.APPLET, "", s"k$i", JsNull, now)
    }

    // When
    for
      rejected <- status(context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "new", "value" -> 1, "scope" -> "APPLET")))
      _ <- context.call(Fixtures.Owner, "storage.set", Json.obj("key" -> "new", "value" -> 1, "scope" -> "APPLET"), Fixtures.TeamB)
    yield
      // Then
      assertEquals(rejected, 400)
      assertEquals(context.storage.entries.count(_.teamId == Fixtures.TeamB), 1)
  }

  // A function that needs the read_projects app permission and records the team it ran for.
  private final class ProjectFunction extends PlatformFunctionHandler:
    var calledFor: Option[Long] = None
    val definition: PlatformFunction = PlatformFunction("project.list", "project", "", Nil, "", Some("read_projects"))
    def invoke(context: InvocationContext, arguments: FunctionArguments): Future[JsValue] =
      calledFor = Some(context.teamId)
      Future.successful(Json.obj("ok" -> true))

  test("execute / 앱 권한이 필요한 함수 / 승인되고 게시 버전이 요청한 권한일 때만 실행한다") {
    // Given
    def runtime(requested: Seq[String], granted: Seq[String]) =
      val function = ProjectFunction()
      AppletRuntime(PlatformFunctionRegistry(Seq(function)), requested = requested, granted = granted) -> function
    val cases = Seq(
      (Seq("read_projects"), Nil),
      (Nil, Seq("read_projects")),
      (Seq("read_projects"), Seq("read_projects"))
    ).map(runtime)

    // When
    val results = cases.map((runtime, _) =>
      runtime.invoke.execute(Fixtures.Other, Fixtures.TeamA, "applet-1", "project.list", Json.obj()).map(_ => 200).recover {
        case error: ExpectedException => error.status
      }
    )

    // Then
    Future.sequence(results).map { codes =>
      assertEquals(codes, Seq(403, 403, 200))
      assertEquals(cases.map(_._2.calledFor), Seq(None, None, Some(Fixtures.TeamA)))
    }
  }
