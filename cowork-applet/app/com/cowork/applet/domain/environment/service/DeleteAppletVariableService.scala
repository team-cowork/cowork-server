package com.cowork.applet.domain.environment.service

import com.cowork.applet.domain.environment.service.impl.DeleteAppletVariableServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[DeleteAppletVariableServiceImpl])
trait DeleteAppletVariableService:
  def execute(userId: String, appletId: String, name: String): Future[Unit]
