package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.AppletLaunch
import com.cowork.applet.domain.applet.service.impl.LaunchAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[LaunchAppletServiceImpl])
trait LaunchAppletService:
  def execute(userId: String, appletId: String): Future[AppletLaunch]
