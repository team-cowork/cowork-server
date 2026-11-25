package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.{AppletVersionStatus, BundleFile}
import com.cowork.applet.domain.applet.service.impl.SubmitAppletVersionServiceImpl
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.support.{Fixtures, InMemoryAppletRepository, InMemoryAppletVersionRepository}
import scala.concurrent.{ExecutionContext, Future}

class SubmitAppletVersionServiceImplTest extends munit.FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val safeFiles = Seq(BundleFile("index.html", "<p>hi</p>"))
  private val unsafeFiles = Seq(BundleFile("index.html", "<script>eval(x)</script>"))

  private final class Context:
    val appletRepository = InMemoryAppletRepository(Fixtures.applet())
    val versionRepository = InMemoryAppletVersionRepository(appletRepository)
    val service = SubmitAppletVersionServiceImpl(AppletReader(appletRepository), versionRepository)

  test("execute / 검사를 통과한 번들 / 파일을 저장하고 즉시 게시한다") {
    // Given
    val context = Context()

    // When
    context.service.execute(Fixtures.Owner, "applet-1", safeFiles).map { version =>
      // Then
      assertEquals(version.status, AppletVersionStatus.APPROVED)
      assertEquals(version.number, 1)
      assertEquals(context.versionRepository.versions.head.files, safeFiles)
      assertEquals(context.appletRepository.applets("applet-1").publishedVersionId, Some(version.versionId))
    }
  }

  test("execute / 차단 항목이 있는 번들 / 검사 결과만 저장하고 게시 버전을 바꾸지 않는다") {
    // Given
    val context = Context()

    // When
    context.service.execute(Fixtures.Owner, "applet-1", unsafeFiles).map { version =>
      // Then
      assertEquals(version.status, AppletVersionStatus.REJECTED)
      assertEquals(version.findings.map(_.ruleId), Seq("DYNAMIC_CODE"))
      assertEquals(context.versionRepository.versions.head.files, Nil)
      assertEquals(context.appletRepository.applets("applet-1").publishedVersionId, None)
    }
  }

  test("execute / 거부된 버전 다음 업로드 / 버전 번호를 이어서 매긴다") {
    // Given
    val context = Context()

    // When
    for
      _ <- context.service.execute(Fixtures.Owner, "applet-1", unsafeFiles)
      second <- context.service.execute(Fixtures.Owner, "applet-1", safeFiles)
    yield
      // Then
      assertEquals(second.number, 2)
  }

  test("execute / 소유자가 아닌 사용자 / 저장하지 않고 거부한다") {
    // Given
    val context = Context()

    // When
    context.service.execute(Fixtures.Other, "applet-1", safeFiles).failed.map { error =>
      // Then
      assertEquals(error.asInstanceOf[ExpectedException].status, 404)
      assertEquals(context.versionRepository.versions.size, 0)
    }
  }

  test("execute / 통과 버전이 계속 쌓임 / 게시 버전과 최근 5개만 파일을 보관한다") {
    // Given
    val context = Context()

    // When
    (1 to 7).foldLeft(Future.unit)((previous, _) =>
      previous.flatMap(_ => context.service.execute(Fixtures.Owner, "applet-1", safeFiles).map(_ => ()))
    ).map { _ =>
      // Then
      val statuses = context.versionRepository.versions.sortBy(_.number).map(_.status)
      assertEquals(statuses.take(1).toList, List(AppletVersionStatus.ARCHIVED))
      assertEquals(statuses.count(_ == AppletVersionStatus.APPROVED), 6)
    }
  }
