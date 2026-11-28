package com.cowork.applet.domain.function.service.impl

import com.cowork.applet.domain.applet.service.AppletPermissions
import com.cowork.applet.domain.function.model.{FunctionArguments, InvocationContext}
import com.cowork.applet.domain.function.service.{InvokePlatformFunctionService, PlatformFunctionRegistry}
import com.cowork.applet.domain.installation.model.InstalledApplet
import com.cowork.applet.domain.installation.service.InstalledAppletAccess
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.{FORBIDDEN, NOT_FOUND}
import play.api.libs.json.{JsObject, JsValue}
import scala.concurrent.{ExecutionContext, Future}

// The applet runs on the client, so any user who can run it can also call these functions directly.
// Functions must treat their arguments as untrusted input.
// A function that declares an app permission runs only when the installing team granted it. The caller's own team
// role permissions do not widen or narrow what the applet can reach.
@Singleton
class InvokePlatformFunctionServiceImpl @Inject() (
    installedAppletAccess: InstalledAppletAccess,
    appletPermissions: AppletPermissions,
    platformFunctionRegistry: PlatformFunctionRegistry
)(using ExecutionContext)
    extends InvokePlatformFunctionService:

  override def execute(userId: String, teamId: Long, appletId: String, functionName: String, arguments: JsObject): Future[JsValue] =
    platformFunctionRegistry.find(functionName) match
      case None => Future.failed(ExpectedException(NOT_FOUND, "플랫폼 함수를 찾을 수 없습니다."))
      case Some(handler) =>
        for
          installed <- installedAppletAccess.usable(teamId, userId, appletId)
          _ <- handler.definition.permission.fold(Future.unit)(requireGranted(installed, _))
          result <- handler.invoke(InvocationContext(installed.applet.appletId, teamId, userId), FunctionArguments(arguments))
        yield result

  private def requireGranted(installed: InstalledApplet, permission: String): Future[Unit] =
    appletPermissions.requestedBy(installed.applet).map { requested =>
      if !installed.installation.allows(permission, requested) then
        throw ExpectedException(FORBIDDEN, "팀이 앱에 승인하지 않은 권한이 필요한 함수입니다.")
    }
