package com.cowork.applet.domain.installation.service.impl

import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.installation.model.InstalledApplet
import com.cowork.applet.domain.installation.repository.AppletInstallationRepository
import com.cowork.applet.domain.installation.service.QueryTeamAppletsService
import com.cowork.applet.domain.team.service.TeamAccess
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

// An installed applet that its owner later unpublished stays installed but is left out until it is published again.
@Singleton
class QueryTeamAppletsServiceImpl @Inject() (
    teamAccess: TeamAccess,
    appletRepository: AppletRepository,
    appletInstallationRepository: AppletInstallationRepository
)(using ExecutionContext)
    extends QueryTeamAppletsService:

  override def execute(userId: String, teamId: Long): Future[Seq[InstalledApplet]] =
    for
      _ <- teamAccess.requireMember(teamId, userId)
      installations <- appletInstallationRepository.findByTeam(teamId)
      applets <- Future.traverse(installations)(installation => appletRepository.findById(installation.appletId))
    yield installations.zip(applets).collect {
      case (installation, Some(applet)) if applet.isPublished => InstalledApplet(installation, applet)
    }
