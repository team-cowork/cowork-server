package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.service.impl.QueryMyAppletsServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryMyAppletsServiceImpl])
trait QueryMyAppletsService:
  def execute(userId: String): Future[Seq[Applet]]
