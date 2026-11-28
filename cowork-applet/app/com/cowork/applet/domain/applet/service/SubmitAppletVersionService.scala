package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.{AppletVersion, BundleFile}
import com.cowork.applet.domain.applet.service.impl.SubmitAppletVersionServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[SubmitAppletVersionServiceImpl])
trait SubmitAppletVersionService:
  def execute(userId: String, appletId: String, files: Seq[BundleFile], permissions: Seq[String]): Future[AppletVersion]
