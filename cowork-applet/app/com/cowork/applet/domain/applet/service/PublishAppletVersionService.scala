package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.service.impl.PublishAppletVersionServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[PublishAppletVersionServiceImpl])
trait PublishAppletVersionService:
  def execute(userId: String, appletId: String, versionId: String): Future[Applet]
