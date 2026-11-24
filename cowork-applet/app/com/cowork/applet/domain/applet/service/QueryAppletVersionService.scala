package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.AppletVersion
import com.cowork.applet.domain.applet.service.impl.QueryAppletVersionServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryAppletVersionServiceImpl])
trait QueryAppletVersionService:
  def execute(userId: String, appletId: String, versionId: String): Future[AppletVersion]
