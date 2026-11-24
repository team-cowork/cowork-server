package com.cowork.applet.domain.network.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.repository.AppletHttpTargetRepository
import com.cowork.applet.domain.network.service.QueryAppletHttpTargetsService
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class QueryAppletHttpTargetsServiceImpl @Inject() (
    appletReader: AppletReader,
    appletHttpTargetRepository: AppletHttpTargetRepository
)(using ExecutionContext)
    extends QueryAppletHttpTargetsService:

  override def execute(userId: String, appletId: String): Future[Seq[AppletHttpTarget]] =
    appletReader.owned(appletId, userId).flatMap(applet => appletHttpTargetRepository.findAll(applet.appletId))
