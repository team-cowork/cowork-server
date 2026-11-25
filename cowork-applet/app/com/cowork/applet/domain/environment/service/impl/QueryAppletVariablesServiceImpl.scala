package com.cowork.applet.domain.environment.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.environment.model.AppletVariable
import com.cowork.applet.domain.environment.repository.AppletVariableRepository
import com.cowork.applet.domain.environment.service.QueryAppletVariablesService
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class QueryAppletVariablesServiceImpl @Inject() (
    appletReader: AppletReader,
    appletVariableRepository: AppletVariableRepository
)(using ExecutionContext)
    extends QueryAppletVariablesService:

  override def execute(userId: String, appletId: String): Future[Seq[AppletVariable]] =
    appletReader.owned(appletId, userId).flatMap(applet => appletVariableRepository.findAll(applet.appletId))
