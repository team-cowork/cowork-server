package com.cowork.applet.domain.environment.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.repository.AppletSecretRepository
import com.cowork.applet.domain.environment.service.QueryAppletSecretsService
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class QueryAppletSecretsServiceImpl @Inject() (
    appletReader: AppletReader,
    appletSecretRepository: AppletSecretRepository
)(using ExecutionContext)
    extends QueryAppletSecretsService:

  override def execute(userId: String, appletId: String): Future[Seq[AppletSecret]] =
    appletReader.owned(appletId, userId).flatMap(applet => appletSecretRepository.findAll(applet.appletId))
