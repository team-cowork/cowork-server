package com.cowork.applet.domain.environment.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.environment.repository.AppletVariableRepository
import com.cowork.applet.domain.environment.service.DeleteAppletVariableService
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeleteAppletVariableServiceImpl @Inject() (
    appletReader: AppletReader,
    appletVariableRepository: AppletVariableRepository
)(using ExecutionContext)
    extends DeleteAppletVariableService:

  override def execute(userId: String, appletId: String, name: String): Future[Unit] =
    for
      applet <- appletReader.owned(appletId, userId)
      deleted <- appletVariableRepository.delete(applet.appletId, name)
    yield if !deleted then throw ExpectedException(NOT_FOUND, "환경변수를 찾을 수 없습니다.")
