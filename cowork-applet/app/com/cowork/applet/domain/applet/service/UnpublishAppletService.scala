package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.service.impl.UnpublishAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[UnpublishAppletServiceImpl])
trait UnpublishAppletService:
  def execute(userId: String, appletId: String): Future[Applet]
