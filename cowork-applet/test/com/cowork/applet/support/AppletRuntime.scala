package com.cowork.applet.support

import com.cowork.applet.domain.applet.service.AppletPermissions
import com.cowork.applet.domain.function.service.PlatformFunctionRegistry
import com.cowork.applet.domain.function.service.impl.InvokePlatformFunctionServiceImpl
import com.cowork.applet.domain.installation.model.AppletInstallation
import com.cowork.applet.domain.installation.service.InstalledAppletAccess
import com.cowork.applet.domain.team.service.TeamAccess
import java.time.Instant
import scala.concurrent.ExecutionContext

// applet-1 with its published version-1, installed in the given teams.
// Owner and Other are members of TeamA, TeamB, and NoUseTeam. Only TeamA and TeamB let members use applets.
final class AppletRuntime(
    registry: PlatformFunctionRegistry,
    installedIn: Seq[Long] = Seq(Fixtures.TeamA, Fixtures.NoUseTeam),
    published: Boolean = true,
    requested: Seq[String] = Nil,
    granted: Seq[String] = Nil
)(using ExecutionContext):
  val applets = InMemoryAppletRepository(Fixtures.applet(published))
  val versions = InMemoryAppletVersionRepository(applets)
  versions.versions += Fixtures.version(requested)
  val installations = InMemoryAppletInstallationRepository()
  installations.installations ++= installedIn.map(team => AppletInstallation(team, "applet-1", Fixtures.Owner, Instant.EPOCH, granted))

  private val members =
    for
      team <- Set(Fixtures.TeamA, Fixtures.TeamB, Fixtures.NoUseTeam)
      user <- Set(Fixtures.Owner, Fixtures.Other)
    yield team -> user.toLong

  val access = InstalledAppletAccess(
    TeamAccess(FixedTeamProjectionReadiness(true), Fixtures.appletUsers(members)),
    applets,
    installations
  )
  val permissions = AppletPermissions(versions)
  val invoke = InvokePlatformFunctionServiceImpl(access, permissions, registry)
