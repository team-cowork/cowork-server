package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.service.{AppletReader, QueryAppletService}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class QueryAppletServiceImpl @Inject() (appletReader: AppletReader) extends QueryAppletService:
  override def execute(userId: String, appletId: String): Future[Applet] = appletReader.visible(appletId, userId)
