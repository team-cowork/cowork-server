package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.service.impl.DeleteAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[DeleteAppletServiceImpl])
trait DeleteAppletService:
  def execute(userId: String, appletId: String): Future[Unit]
