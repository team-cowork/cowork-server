package com.cowork.applet.domain.installation.service

import com.cowork.applet.domain.installation.service.impl.UninstallAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[UninstallAppletServiceImpl])
trait UninstallAppletService:
  def execute(userId: String, teamId: Long, appletId: String): Future[Unit]
