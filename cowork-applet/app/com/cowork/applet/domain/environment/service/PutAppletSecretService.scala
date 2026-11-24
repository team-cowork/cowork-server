package com.cowork.applet.domain.environment.service

import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.service.impl.PutAppletSecretServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[PutAppletSecretServiceImpl])
trait PutAppletSecretService:
  def execute(userId: String, appletId: String, name: String, value: String): Future[AppletSecret]
