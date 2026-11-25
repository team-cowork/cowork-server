package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.{Applet, AppletVersionStatus}
import com.cowork.applet.domain.applet.repository.{AppletRepository, AppletVersionRepository}
import com.cowork.applet.domain.applet.service.{AppletReader, PublishAppletVersionService}
import com.cowork.applet.global.error.ExpectedException
import java.time.Instant
import javax.inject.{Inject, Singleton}
import play.api.http.Status.{CONFLICT, NOT_FOUND}
import scala.concurrent.{ExecutionContext, Future}

// Rolls the applet back or forward to an approved version that still has its files.
@Singleton
class PublishAppletVersionServiceImpl @Inject() (
    appletReader: AppletReader,
    appletRepository: AppletRepository,
    appletVersionRepository: AppletVersionRepository
)(using ExecutionContext)
    extends PublishAppletVersionService:

  override def execute(userId: String, appletId: String, versionId: String): Future[Applet] =
    for
      applet <- appletReader.owned(appletId, userId)
      version <- appletVersionRepository
        .findSummary(applet.appletId, versionId)
        .map(_.getOrElse(throw ExpectedException(NOT_FOUND, "앱 버전을 찾을 수 없습니다.")))
      _ = if version.status != AppletVersionStatus.APPROVED then
        throw ExpectedException(CONFLICT, "검사를 통과했고 파일이 보관된 버전만 게시할 수 있습니다.")
      now = Instant.now()
      _ <- appletRepository.updatePublishedVersion(applet.appletId, Some(version.versionId), now)
    yield applet.copy(publishedVersionId = Some(version.versionId), updatedAt = now)
