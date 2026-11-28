package com.cowork.applet.domain.environment.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.environment.repository.AppletSecretRepository
import com.cowork.applet.domain.environment.service.DeleteAppletSecretService
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeleteAppletSecretServiceImpl @Inject() (
    appletReader: AppletReader,
    appletSecretRepository: AppletSecretRepository
)(using ExecutionContext)
    extends DeleteAppletSecretService:

  override def execute(userId: String, appletId: String, name: String): Future[Unit] =
    for
      applet <- appletReader.owned(appletId, userId)
      deleted <- appletSecretRepository.delete(applet.appletId, name)
    yield if !deleted then throw ExpectedException(NOT_FOUND, "시크릿을 찾을 수 없습니다.")
