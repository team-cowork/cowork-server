package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.service.impl.CreateAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[CreateAppletServiceImpl])
trait CreateAppletService:
  def execute(userId: String, name: String, description: Option[String]): Future[Applet]
