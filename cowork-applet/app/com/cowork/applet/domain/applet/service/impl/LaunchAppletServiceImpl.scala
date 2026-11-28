package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.{Applet, AppletLaunch, AppletVersion}
import com.cowork.applet.domain.applet.repository.AppletVersionRepository
import com.cowork.applet.domain.applet.service.LaunchAppletService
import com.cowork.applet.domain.environment.repository.AppletVariableRepository
import com.cowork.applet.domain.installation.service.InstalledAppletAccess
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import scala.concurrent.{ExecutionContext, Future}

// Secrets are not part of the launch payload because the bundle runs on the user's computer.
@Singleton
class LaunchAppletServiceImpl @Inject() (
    installedAppletAccess: InstalledAppletAccess,
    appletVersionRepository: AppletVersionRepository,
    appletVariableRepository: AppletVariableRepository
)(using ExecutionContext)
    extends LaunchAppletService:

  override def execute(userId: String, teamId: Long, appletId: String): Future[AppletLaunch] =
    for
      applet <- installedAppletAccess.usable(teamId, userId, appletId).map(_.applet)
      version <- publishedVersion(applet)
      variables <- appletVariableRepository.findAll(applet.appletId)
    yield AppletLaunch(applet, version, LaunchAppletServiceImpl.environment(applet, version, variables.map(v => v.name -> v.value)))

  private def publishedVersion(applet: Applet): Future[AppletVersion] =
    applet.publishedVersionId match
      case None => Future.failed(LaunchAppletServiceImpl.notPublished)
      case Some(versionId) =>
        appletVersionRepository
          .findWithFiles(applet.appletId, versionId)
          .map(_.getOrElse(throw LaunchAppletServiceImpl.notPublished))

object LaunchAppletServiceImpl:
  private def notPublished = ExpectedException(NOT_FOUND, "게시된 앱 버전이 없습니다.")

  def environment(applet: Applet, version: AppletVersion, variables: Seq[(String, String)]): Map[String, String] =
    variables.toMap ++ Map(
      "COWORK_APPLET_ID" -> applet.appletId,
      "COWORK_APPLET_VERSION_ID" -> version.versionId
    )
