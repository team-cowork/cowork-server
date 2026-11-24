package com.cowork.applet.domain.network.service

import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.service.impl.PutAppletHttpTargetServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[PutAppletHttpTargetServiceImpl])
trait PutAppletHttpTargetService:
  def execute(
      userId: String,
      appletId: String,
      host: String,
      headers: Map[String, String],
      query: Map[String, String]
  ): Future[AppletHttpTarget]
