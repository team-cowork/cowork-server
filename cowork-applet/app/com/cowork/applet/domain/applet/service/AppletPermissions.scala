package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.AppletVersionRepository
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.{BAD_REQUEST, CONFLICT}
import scala.concurrent.{ExecutionContext, Future}

// Reads the app permissions that the published version requests.
@Singleton
class AppletPermissions @Inject() (appletVersionRepository: AppletVersionRepository)(using ExecutionContext):

  def requestedBy(applet: Applet): Future[Seq[String]] =
    applet.publishedVersionId match
      case None => Future.successful(Nil)
      case Some(versionId) =>
        appletVersionRepository.findSummary(applet.appletId, versionId).map(_.fold(Nil)(_.permissions))

object AppletPermissions:
  def requested(permissions: Seq[String], catalog: Set[String]): Seq[String] =
    if !permissions.forall(catalog.contains) then throw ExpectedException(BAD_REQUEST, "요청할 수 없는 앱 권한이 있습니다.")
    permissions.distinct.sorted

  // The approver names the permissions, so a version published after the approver read them cannot slip in new ones.
  def approved(permissions: Seq[String], requested: Seq[String]): Seq[String] =
    if !permissions.forall(requested.contains) then
      throw ExpectedException(CONFLICT, "게시된 버전이 요청하지 않은 앱 권한이 있습니다.")
    permissions.distinct.sorted
