package com.cowork.applet.domain.installation.presentation.controller

import com.cowork.applet.domain.installation.presentation.data.request.AppletPermissionsReqDto
import com.cowork.applet.domain.installation.presentation.data.response.InstalledAppletResDto
import com.cowork.applet.domain.installation.service.{
  ApproveAppletPermissionsService,
  InstallAppletService,
  QueryTeamAppletsService,
  UninstallAppletService
}
import com.cowork.applet.global.auth.RequestUser
import com.cowork.applet.global.json.RequestBodies
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext

class TeamAppletController @Inject() (
    components: ControllerComponents,
    queryTeamAppletsService: QueryTeamAppletsService,
    installAppletService: InstallAppletService,
    uninstallAppletService: UninstallAppletService,
    approveAppletPermissionsService: ApproveAppletPermissionsService
)(using ExecutionContext)
    extends AbstractController(components):

  def list(teamId: Long): Action[AnyContent] = Action.async { request =>
    queryTeamAppletsService
      .execute(RequestUser.id(request), teamId)
      .map(installed => Ok(Json.toJson(installed.map(InstalledAppletResDto.from))))
  }

  // The body is optional. Without it the installation grants no app permissions.
  def install(teamId: Long, appletId: String): Action[AnyContent] = Action.async { request =>
    val permissions = request.body.asJson.fold(Nil)(json => RequestBodies.read[AppletPermissionsReqDto](json).permissions)
    installAppletService
      .execute(RequestUser.id(request), teamId, appletId, permissions)
      .map(installed => Ok(Json.toJson(InstalledAppletResDto.from(installed))))
  }

  def uninstall(teamId: Long, appletId: String): Action[AnyContent] = Action.async { request =>
    uninstallAppletService.execute(RequestUser.id(request), teamId, appletId).map(_ => NoContent)
  }

  def approvePermissions(teamId: Long, appletId: String): Action[JsValue] = Action.async(parse.json) { request =>
    val body = RequestBodies.read[AppletPermissionsReqDto](request.body)
    approveAppletPermissionsService
      .execute(RequestUser.id(request), teamId, appletId, body.permissions)
      .map(installed => Ok(Json.toJson(InstalledAppletResDto.from(installed))))
  }
