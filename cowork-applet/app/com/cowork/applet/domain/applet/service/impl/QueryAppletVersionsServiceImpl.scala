package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.AppletVersion
import com.cowork.applet.domain.applet.repository.AppletVersionRepository
import com.cowork.applet.domain.applet.service.{AppletReader, QueryAppletVersionsService}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class QueryAppletVersionsServiceImpl @Inject() (
    appletReader: AppletReader,
    appletVersionRepository: AppletVersionRepository
)(using ExecutionContext)
    extends QueryAppletVersionsService:

  override def execute(userId: String, appletId: String): Future[Seq[AppletVersion]] =
    appletReader.owned(appletId, userId).flatMap(applet => appletVersionRepository.findSummaries(applet.appletId))
