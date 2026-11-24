package com.cowork.applet.domain.environment.service

import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.service.impl.QueryAppletSecretsServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryAppletSecretsServiceImpl])
trait QueryAppletSecretsService:
  def execute(userId: String, appletId: String): Future[Seq[AppletSecret]]
