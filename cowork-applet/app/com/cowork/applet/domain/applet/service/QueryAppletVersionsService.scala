package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.AppletVersion
import com.cowork.applet.domain.applet.service.impl.QueryAppletVersionsServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryAppletVersionsServiceImpl])
trait QueryAppletVersionsService:
  def execute(userId: String, appletId: String): Future[Seq[AppletVersion]]
