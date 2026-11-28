package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.{AppletVersion, AppletVersionStatus, BundleFile}
import com.cowork.applet.domain.applet.service.impl.{PublishAppletVersionServiceImpl, UnpublishAppletServiceImpl}
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.support.{Fixtures, InMemoryAppletRepository, InMemoryAppletVersionRepository}
import java.time.Instant
import scala.concurrent.ExecutionContext

class PublishAppletVersionServiceImplTest extends munit.FunSuite:

  private given ExecutionContext = munitExecutionContext

  private final class Context:
    val appletRepository = InMemoryAppletRepository(Fixtures.applet(published = true))
    val versionRepository = InMemoryAppletVersionRepository(appletRepository)
    versionRepository.versions ++= Seq(
      AppletVersion("version-1", "applet-1", 1, AppletVersionStatus.APPROVED, Nil, Seq(BundleFile("index.html", "")), Instant.EPOCH),
      AppletVersion("version-2", "applet-1", 2, AppletVersionStatus.ARCHIVED, Nil, Nil, Instant.EPOCH),
      AppletVersion("version-3", "applet-1", 3, AppletVersionStatus.REJECTED, Nil, Nil, Instant.EPOCH)
    )
    val reader = AppletReader(appletRepository)
    val publish = PublishAppletVersionServiceImpl(reader, appletRepository, versionRepository)
    val unpublish = UnpublishAppletServiceImpl(reader, appletRepository)

  test("publish / 파일이 보관된 통과 버전 / 게시 버전으로 바꾼다") {
    // Given
    val context = Context()
    context.appletRepository.applets.updateWith("applet-1")(_.map(_.copy(publishedVersionId = Some("other"))))

    // When
    context.publish.execute(Fixtures.Owner, "applet-1", "version-1").map { applet =>
      // Then
      assertEquals(applet.publishedVersionId, Some("version-1"))
      assertEquals(context.appletRepository.applets("applet-1").publishedVersionId, Some("version-1"))
    }
  }

  test("publish / 보관 처리된 버전과 거부된 버전 / 409로 거부한다") {
    // Given
    val context = Context()

    // When
    for
      archived <- context.publish.execute(Fixtures.Owner, "applet-1", "version-2").failed
      rejected <- context.publish.execute(Fixtures.Owner, "applet-1", "version-3").failed
    yield
      // Then
      assertEquals(archived.asInstanceOf[ExpectedException].status, 409)
      assertEquals(rejected.asInstanceOf[ExpectedException].status, 409)
  }

  test("unpublish / 게시된 앱 / 게시 버전을 비워 다른 사용자에게 숨긴다") {
    // Given
    val context = Context()

    // When
    context.unpublish.execute(Fixtures.Owner, "applet-1").map { applet =>
      // Then
      assertEquals(applet.publishedVersionId, None)
      intercept[ExpectedException](AppletReader.requireVisible(context.appletRepository.applets("applet-1"), Fixtures.Other))
    }
  }
