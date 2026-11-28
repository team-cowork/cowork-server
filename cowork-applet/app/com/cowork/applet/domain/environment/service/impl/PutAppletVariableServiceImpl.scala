package com.cowork.applet.domain.environment.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.environment.model.AppletVariable
import com.cowork.applet.domain.environment.repository.AppletVariableRepository
import com.cowork.applet.domain.environment.service.{EnvironmentEntries, PutAppletVariableService}
import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PutAppletVariableServiceImpl @Inject() (
    appletReader: AppletReader,
    appletVariableRepository: AppletVariableRepository
)(using ExecutionContext)
    extends PutAppletVariableService:

  override def execute(userId: String, appletId: String, name: String, value: String): Future[AppletVariable] =
    val validName = EnvironmentEntries.name(name)
    val validValue = EnvironmentEntries.value(value)
    for
      applet <- appletReader.owned(appletId, userId)
      existing <- appletVariableRepository.findAll(applet.appletId)
      _ = EnvironmentEntries.requireCapacity(existing.exists(_.name == validName), existing.size)
      variable = AppletVariable(applet.appletId, validName, validValue, Instant.now())
      _ <- appletVariableRepository.upsert(variable)
    yield variable
