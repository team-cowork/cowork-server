package com.cowork.applet.domain.installation.service.impl

import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.applet.service.AppletPermissions
import com.cowork.applet.domain.installation.model.{AppletInstallation, InstalledApplet}
import com.cowork.applet.domain.installation.repository.AppletInstallationRepository
import com.cowork.applet.domain.installation.service.InstallAppletService
import com.cowork.applet.domain.team.service.TeamAccess
import com.cowork.applet.global.error.ExpectedException
import java.time.Instant
import javax.inject.{Inject, Singleton}
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND}
import scala.concurrent.{ExecutionContext, Future}

// Only published applets can be installed. The installer grants the app permissions that it names, and each must be
// requested by the published version. Installing an applet that is already installed changes nothing.
@Singleton
class InstallAppletServiceImpl @Inject() (
    teamAccess: TeamAccess,
    appletRepository: AppletRepository,
    appletPermissions: AppletPermissions,
    appletInstallationRepository: AppletInstallationRepository
)(using ExecutionContext)
    extends InstallAppletService:

  override def execute(userId: String, teamId: Long, appletId: String, permissions: Seq[String]): Future[InstalledApplet] =
    for
      _ <- teamAccess.requireAppletManager(teamId, userId)
      applet <- appletRepository
        .findById(appletId)
        .map(_.filter(_.isPublished).getOrElse(throw ExpectedException(NOT_FOUND, "설치할 수 있는 게시된 앱을 찾을 수 없습니다.")))
      installed <- appletInstallationRepository.findByTeam(teamId)
      result <- installed.find(_.appletId == appletId) match
        case Some(existing) => Future.successful(InstalledApplet(existing, applet))
        case None =>
          if installed.size >= AppletInstallation.MaxPerTeam then
            throw ExpectedException(BAD_REQUEST, "팀 하나에 앱을 최대 50개까지 설치할 수 있습니다.")
          appletPermissions.requestedBy(applet).flatMap { requested =>
            val granted = AppletPermissions.approved(permissions, requested)
            val installation = AppletInstallation(teamId, appletId, userId, Instant.now(), granted)
            appletInstallationRepository.create(installation).map(_ => InstalledApplet(installation, applet))
          }
    yield result
