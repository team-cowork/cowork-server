package com.cowork.applet.domain.installation.service.impl

import com.cowork.applet.domain.applet.service.AppletPermissions
import com.cowork.applet.domain.installation.model.InstalledApplet
import com.cowork.applet.domain.installation.repository.AppletInstallationRepository
import com.cowork.applet.domain.installation.service.{ApproveAppletPermissionsService, InstalledAppletAccess}
import com.cowork.applet.domain.team.service.TeamAccess
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

// Replaces the granted app permissions of an installation. A permission left out is no longer granted.
@Singleton
class ApproveAppletPermissionsServiceImpl @Inject() (
    teamAccess: TeamAccess,
    installedAppletAccess: InstalledAppletAccess,
    appletPermissions: AppletPermissions,
    appletInstallationRepository: AppletInstallationRepository
)(using ExecutionContext)
    extends ApproveAppletPermissionsService:

  override def execute(userId: String, teamId: Long, appletId: String, permissions: Seq[String]): Future[InstalledApplet] =
    for
      _ <- teamAccess.requireAppletManager(teamId, userId)
      installed <- installedAppletAccess.installed(teamId, appletId)
      requested <- appletPermissions.requestedBy(installed.applet)
      granted = AppletPermissions.approved(permissions, requested)
      updated <- appletInstallationRepository.updateGrantedPermissions(teamId, appletId, granted)
    yield
      if !updated then throw InstalledAppletAccess.notFound
      installed.copy(installation = installed.installation.copy(grantedPermissions = granted))
