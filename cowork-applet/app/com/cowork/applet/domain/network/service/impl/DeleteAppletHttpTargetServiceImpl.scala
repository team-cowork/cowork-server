package com.cowork.applet.domain.network.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.network.repository.AppletHttpTargetRepository
import com.cowork.applet.domain.network.service.DeleteAppletHttpTargetService
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeleteAppletHttpTargetServiceImpl @Inject() (
    appletReader: AppletReader,
    appletHttpTargetRepository: AppletHttpTargetRepository
)(using ExecutionContext)
    extends DeleteAppletHttpTargetService:

  override def execute(userId: String, appletId: String, host: String): Future[Unit] =
    for
      applet <- appletReader.owned(appletId, userId)
      deleted <- appletHttpTargetRepository.delete(applet.appletId, host.toLowerCase)
    yield if !deleted then throw ExpectedException(NOT_FOUND, "HTTP 대상을 찾을 수 없습니다.")
