package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.AppletDetail
import com.cowork.applet.domain.applet.service.impl.QueryAppletServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryAppletServiceImpl])
trait QueryAppletService:
  def execute(userId: String, appletId: String): Future[AppletDetail]
