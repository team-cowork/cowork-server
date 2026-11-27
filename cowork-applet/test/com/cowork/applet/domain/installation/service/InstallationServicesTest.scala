package com.cowork.applet.domain.installation.service

import com.cowork.applet.domain.applet.service.AppletPermissions
import com.cowork.applet.domain.installation.model.AppletInstallation
import com.cowork.applet.domain.installation.service.impl.{
  ApproveAppletPermissionsServiceImpl,
  InstallAppletServiceImpl,
  QueryTeamAppletsServiceImpl,
  UninstallAppletServiceImpl
}
import com.cowork.applet.domain.team.model.TeamRolePolicy
import com.cowork.applet.domain.team.service.TeamAccess
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.support.{
  Fixtures,
  FixedTeamProjectionReadiness,
  InMemoryAppletInstallationRepository,
  InMemoryAppletRepository,
  InMemoryAppletVersionRepository,
  InMemoryTeamProjectionRepository
}
import java.time.Instant
import play.api.libs.json.Json
import scala.concurrent.{ExecutionContext, Future}

class InstallationServicesTest extends munit.FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val teamId = 3L
  private val manager = "7"
  private val member = "8"
  private val outsider = "9"

  private val policies = Seq(
    TeamRolePolicy("role:3:5", teamId, "ROLE", Some(5), None, false, Json.obj("manage_applets" -> true), false, 1),
    TeamRolePolicy("assignment:3:7:5", teamId, "ASSIGNMENT", Some(5), Some(7), false, Json.obj(), false, 2)
  )

  private final class Context(ready: Boolean = true):
    val applets = InMemoryAppletRepository(Fixtures.applet(published = true), Fixtures.applet(appletId = "draft"))
    val versions = InMemoryAppletVersionRepository(applets)
    versions.versions += Fixtures.version(Seq("read_projects", "write_projects"))
    val installations = InMemoryAppletInstallationRepository()
    val access = TeamAccess(FixedTeamProjectionReadiness(ready), InMemoryTeamProjectionRepository(Set(teamId -> 7L, teamId -> 8L), policies))
    val permissions = AppletPermissions(versions)
    val install = InstallAppletServiceImpl(access, applets, permissions, installations)
    val installed = InstalledAppletAccess(access, applets, installations)
    val approve = ApproveAppletPermissionsServiceImpl(access, installed, permissions, installations)
    val uninstall = UninstallAppletServiceImpl(access, installations)
    val query = QueryTeamAppletsServiceImpl(access, applets, installations)

  private def status(result: Future[?]): Future[Int] = result.failed.map(_.asInstanceOf[ExpectedException].status)

  test("install / 앱 관리 권한이 있는 멤버와 게시된 앱 / 팀에 설치한다") {
    // Given
    val context = Context()

    // When
    context.install.execute(manager, teamId, "applet-1", Nil).map { installed =>
      // Then
      assertEquals(installed.installation.installedBy, manager)
      assertEquals(context.installations.installations.map(_.appletId).toList, List("applet-1"))
    }
  }

  test("install / 이미 설치된 앱 / 새로 만들지 않고 기존 설치를 반환한다") {
    // Given
    val context = Context()

    // When
    for
      first <- context.install.execute(manager, teamId, "applet-1", Nil)
      second <- context.install.execute(manager, teamId, "applet-1", Nil)
    yield
      // Then
      assertEquals(second.installation, first.installation)
      assertEquals(context.installations.installations.size, 1)
  }

  test("install / 권한 없는 멤버, 팀 밖 사용자, 숫자가 아닌 사용자 / 403으로 거부한다") {
    // Given
    val context = Context()

    // When
    Future.sequence(Seq(member, outsider, "abc").map(user => status(context.install.execute(user, teamId, "applet-1", Nil)))).map { codes =>
      // Then
      assertEquals(codes, Seq(403, 403, 403))
      assert(context.installations.installations.isEmpty)
    }
  }

  test("install / 게시되지 않은 앱 / 404로 거부한다") {
    // Given
    val context = Context()

    // When
    status(context.install.execute(manager, teamId, "draft", Nil)).map { code =>
      // Then
      assertEquals(code, 404)
    }
  }

  test("install / 팀 설치 개수 제한 / 400으로 거부한다") {
    // Given
    val context = Context()
    (1 to AppletInstallation.MaxPerTeam).foreach(i => context.installations.installations += AppletInstallation(teamId, s"a$i", manager, Instant.EPOCH))

    // When
    status(context.install.execute(manager, teamId, "applet-1", Nil)).map { code =>
      // Then
      assertEquals(code, 400)
    }
  }

  test("install / 팀 권한 정보 동기화 중 / 503으로 거부한다") {
    // Given
    val context = Context(ready = false)

    // When
    status(context.install.execute(manager, teamId, "applet-1", Nil)).map { code =>
      // Then
      assertEquals(code, 503)
    }
  }

  test("query / 팀 멤버 / 게시 상태인 설치 앱만 반환한다") {
    // Given
    val context = Context()
    context.installations.installations ++= Seq(
      AppletInstallation(teamId, "applet-1", manager, Instant.EPOCH),
      AppletInstallation(teamId, "draft", manager, Instant.EPOCH),
      AppletInstallation(teamId, "deleted", manager, Instant.EPOCH)
    )

    // When
    context.query.execute(member, teamId).map { installed =>
      // Then
      assertEquals(installed.map(_.applet.appletId), Seq("applet-1"))
    }
  }

  test("query / 팀 밖 사용자 / 403으로 거부한다") {
    // Given
    val context = Context()

    // When
    status(context.query.execute(outsider, teamId)).map { code =>
      // Then
      assertEquals(code, 403)
    }
  }

  test("uninstall / 설치된 앱과 없는 앱 / 제거하고 없으면 404로 거부한다") {
    // Given
    val context = Context()
    context.installations.installations += AppletInstallation(teamId, "applet-1", manager, Instant.EPOCH)

    // When
    for
      _ <- context.uninstall.execute(manager, teamId, "applet-1")
      missing <- status(context.uninstall.execute(manager, teamId, "applet-1"))
    yield
      // Then
      assert(context.installations.installations.isEmpty)
      assertEquals(missing, 404)
  }

  test("install / 게시 버전이 요청한 앱 권한을 지정 / 지정한 권한만 승인해 설치한다") {
    // Given
    val context = Context()

    // When
    context.install.execute(manager, teamId, "applet-1", Seq("read_projects")).map { installed =>
      // Then
      assertEquals(installed.installation.grantedPermissions, Seq("read_projects"))
      assertEquals(context.installations.installations.head.grantedPermissions, Seq("read_projects"))
    }
  }

  test("install / 게시 버전이 요청하지 않은 앱 권한 / 설치하지 않고 409로 거부한다") {
    // Given
    val context = Context()

    // When
    status(context.install.execute(manager, teamId, "applet-1", Seq("delete_team"))).map { code =>
      // Then
      assertEquals(code, 409)
      assert(context.installations.installations.isEmpty)
    }
  }

  test("approvePermissions / 앱 관리 권한이 있는 멤버 / 승인한 앱 권한을 지정한 목록으로 바꾼다") {
    // Given
    val context = Context()
    context.installations.installations += AppletInstallation(teamId, "applet-1", manager, Instant.EPOCH, Seq("read_projects"))

    // When
    context.approve.execute(manager, teamId, "applet-1", Seq("write_projects")).map { installed =>
      // Then
      assertEquals(installed.installation.grantedPermissions, Seq("write_projects"))
      assertEquals(context.installations.installations.head.grantedPermissions, Seq("write_projects"))
    }
  }

  test("approvePermissions / 권한 없는 멤버, 요청하지 않은 권한, 설치되지 않은 앱 / 403, 409, 404로 거부한다") {
    // Given
    val context = Context()
    context.installations.installations += AppletInstallation(teamId, "applet-1", manager, Instant.EPOCH)

    // When
    for
      forbidden <- status(context.approve.execute(member, teamId, "applet-1", Seq("read_projects")))
      conflict <- status(context.approve.execute(manager, teamId, "applet-1", Seq("delete_team")))
      missing <- status(context.approve.execute(manager, teamId, "draft", Nil))
    yield
      // Then
      assertEquals(Seq(forbidden, conflict, missing), Seq(403, 409, 404))
      assertEquals(context.installations.installations.head.grantedPermissions, Nil)
  }
