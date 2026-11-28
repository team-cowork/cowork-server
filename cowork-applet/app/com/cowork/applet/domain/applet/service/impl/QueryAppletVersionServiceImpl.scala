package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.AppletVersion
import com.cowork.applet.domain.applet.repository.AppletVersionRepository
import com.cowork.applet.domain.applet.service.{AppletReader, QueryAppletVersionService}
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class QueryAppletVersionServiceImpl @Inject() (
    appletReader: AppletReader,
    appletVersionRepository: AppletVersionRepository
)(using ExecutionContext)
    extends QueryAppletVersionService:

  override def execute(userId: String, appletId: String, versionId: String): Future[AppletVersion] =
    appletReader.owned(appletId, userId).flatMap { applet =>
      appletVersionRepository
        .findSummary(applet.appletId, versionId)
        .map(_.getOrElse(throw ExpectedException(NOT_FOUND, "앱 버전을 찾을 수 없습니다.")))
    }
