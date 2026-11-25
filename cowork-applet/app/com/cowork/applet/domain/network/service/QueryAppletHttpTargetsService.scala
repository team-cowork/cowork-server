package com.cowork.applet.domain.network.service

import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.service.impl.QueryAppletHttpTargetsServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryAppletHttpTargetsServiceImpl])
trait QueryAppletHttpTargetsService:
  def execute(userId: String, appletId: String): Future[Seq[AppletHttpTarget]]
