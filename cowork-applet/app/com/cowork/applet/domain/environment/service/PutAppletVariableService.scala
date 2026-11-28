package com.cowork.applet.domain.environment.service

import com.cowork.applet.domain.environment.model.AppletVariable
import com.cowork.applet.domain.environment.service.impl.PutAppletVariableServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[PutAppletVariableServiceImpl])
trait PutAppletVariableService:
  def execute(userId: String, appletId: String, name: String, value: String): Future[AppletVariable]
