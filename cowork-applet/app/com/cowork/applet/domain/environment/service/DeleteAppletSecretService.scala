package com.cowork.applet.domain.environment.service

import com.cowork.applet.domain.environment.service.impl.DeleteAppletSecretServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[DeleteAppletSecretServiceImpl])
trait DeleteAppletSecretService:
  def execute(userId: String, appletId: String, name: String): Future[Unit]
