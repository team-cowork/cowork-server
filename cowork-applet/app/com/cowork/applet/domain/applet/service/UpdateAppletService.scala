package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.service.impl.UpdateAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[UpdateAppletServiceImpl])
trait UpdateAppletService:
  def execute(userId: String, appletId: String, name: Option[String], description: Option[String]): Future[Applet]
