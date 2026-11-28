package com.cowork.applet.domain.installation.service

import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.installation.model.InstalledApplet
import com.cowork.applet.domain.installation.repository.AppletInstallationRepository
import com.cowork.applet.domain.team.service.TeamAccess
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import scala.concurrent.{ExecutionContext, Future}

// Publishing only makes an applet installable. It runs only through a team that installed it, and the owner has no
// exception: the owner also needs an installation and the team's applet use permission.
@Singleton
class InstalledAppletAccess @Inject() (
    teamAccess: TeamAccess,
    appletRepository: AppletRepository,
    appletInstallationRepository: AppletInstallationRepository
)(using ExecutionContext):

  def usable(teamId: Long, userId: String, appletId: String): Future[InstalledApplet] =
    teamAccess.requireAppletUser(teamId, userId).flatMap(_ => installed(teamId, appletId))

  // An installed applet that its owner unpublished cannot run until it is published again.
  def installed(teamId: Long, appletId: String): Future[InstalledApplet] =
    appletInstallationRepository.find(teamId, appletId).flatMap {
      case None => Future.failed(InstalledAppletAccess.notFound)
      case Some(installation) =>
        appletRepository.findById(appletId).map {
          case Some(applet) if applet.isPublished => InstalledApplet(installation, applet)
          case _                                  => throw InstalledAppletAccess.notFound
        }
    }

object InstalledAppletAccess:
  def notFound = ExpectedException(NOT_FOUND, "팀에 설치된 앱을 찾을 수 없습니다.")
