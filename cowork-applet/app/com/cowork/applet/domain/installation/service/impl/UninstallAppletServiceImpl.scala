package com.cowork.applet.domain.installation.service.impl

import com.cowork.applet.domain.installation.repository.AppletInstallationRepository
import com.cowork.applet.domain.installation.service.UninstallAppletService
import com.cowork.applet.domain.team.service.TeamAccess
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UninstallAppletServiceImpl @Inject() (teamAccess: TeamAccess, appletInstallationRepository: AppletInstallationRepository)(
    using ExecutionContext
) extends UninstallAppletService:

  override def execute(userId: String, teamId: Long, appletId: String): Future[Unit] =
    for
      _ <- teamAccess.requireAppletManager(teamId, userId)
      deleted <- appletInstallationRepository.delete(teamId, appletId)
    yield if !deleted then throw ExpectedException(NOT_FOUND, "팀에 설치된 앱을 찾을 수 없습니다.")
