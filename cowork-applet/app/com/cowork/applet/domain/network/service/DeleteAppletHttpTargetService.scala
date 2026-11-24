package com.cowork.applet.domain.network.service

import com.cowork.applet.domain.network.service.impl.DeleteAppletHttpTargetServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[DeleteAppletHttpTargetServiceImpl])
trait DeleteAppletHttpTargetService:
  def execute(userId: String, appletId: String, host: String): Future[Unit]
