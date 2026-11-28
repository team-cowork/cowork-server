package com.cowork.applet.domain.environment.service

import com.cowork.applet.domain.environment.model.AppletVariable
import com.cowork.applet.domain.environment.service.impl.QueryAppletVariablesServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryAppletVariablesServiceImpl])
trait QueryAppletVariablesService:
  def execute(userId: String, appletId: String): Future[Seq[AppletVariable]]
