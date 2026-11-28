package com.cowork.applet.domain.installation.service

import com.cowork.applet.domain.installation.model.InstalledApplet
import com.cowork.applet.domain.installation.service.impl.InstallAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[InstallAppletServiceImpl])
trait InstallAppletService:
  def execute(userId: String, teamId: Long, appletId: String, permissions: Seq[String]): Future[InstalledApplet]
