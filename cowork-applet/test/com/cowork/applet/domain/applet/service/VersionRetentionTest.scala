package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.{AppletVersion, AppletVersionStatus}
import java.time.Instant

class VersionRetentionTest extends munit.FunSuite:

  private def version(number: Int, status: AppletVersionStatus) =
    AppletVersion(s"v$number", "applet-1", number, status, Nil, Nil, Instant.EPOCH)

  test("plan / 통과 버전 8개 / 최신 5개를 남기고 오래된 3개를 보관 처리한다") {
    // Given
    val versions = (1 to 8).map(version(_, AppletVersionStatus.APPROVED))

    // When
    val plan = VersionRetention.plan(versions, Some("v8"))

    // Then
    assertEquals(plan.archive, Seq("v2", "v1"))
    assertEquals(plan.delete, Nil)
  }

  test("plan / 오래된 버전이 게시 중 / 게시 버전은 보관 처리하지 않는다") {
    // Given
    val versions = (1 to 7).map(version(_, AppletVersionStatus.APPROVED))

    // When
    val plan = VersionRetention.plan(versions, Some("v1"))

    // Then
    assertEquals(plan.archive, Seq("v2"))
  }

  test("plan / 거부 버전 12개 / 최신 10개를 남기고 나머지를 삭제한다") {
    // Given
    val versions = (1 to 12).map(version(_, AppletVersionStatus.REJECTED))

    // When
    val plan = VersionRetention.plan(versions, None)

    // Then
    assertEquals(plan.delete, Seq("v2", "v1"))
  }

  test("plan / 이미 보관된 버전 / 다시 처리하지 않는다") {
    // Given
    val versions = (1 to 10).map(version(_, AppletVersionStatus.ARCHIVED))

    // When
    val plan = VersionRetention.plan(versions, None)

    // Then
    assertEquals(plan, VersionRetention.Plan(Nil, Nil))
  }
