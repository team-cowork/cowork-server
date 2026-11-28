package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.AppletDetail
import com.cowork.applet.domain.applet.service.{AppletPermissions, AppletReader, QueryAppletService}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

// Files and variables are left out. A user gets them only from the launch API of a team that installed the applet.
@Singleton
class QueryAppletServiceImpl @Inject() (appletReader: AppletReader, appletPermissions: AppletPermissions)(using ExecutionContext)
    extends QueryAppletService:
  override def execute(userId: String, appletId: String): Future[AppletDetail] =
    for
      applet <- appletReader.visible(appletId, userId)
      permissions <- appletPermissions.requestedBy(applet)
    yield AppletDetail(applet, permissions)
