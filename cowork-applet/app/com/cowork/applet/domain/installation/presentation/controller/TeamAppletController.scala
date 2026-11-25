package com.cowork.applet.domain.installation.presentation.controller

import com.cowork.applet.domain.installation.presentation.data.response.InstalledAppletResDto
import com.cowork.applet.domain.installation.service.{InstallAppletService, QueryTeamAppletsService, UninstallAppletService}
import com.cowork.applet.global.auth.RequestUser
import javax.inject.Inject
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext

class TeamAppletController @Inject() (
    components: ControllerComponents,
    queryTeamAppletsService: QueryTeamAppletsService,
    installAppletService: InstallAppletService,
    uninstallAppletService: UninstallAppletService
)(using ExecutionContext)
    extends AbstractController(components):

  def list(teamId: Long): Action[AnyContent] = Action.async { request =>
    queryTeamAppletsService
      .execute(RequestUser.id(request), teamId)
      .map(installed => Ok(Json.toJson(installed.map(InstalledAppletResDto.from))))
  }

  def install(teamId: Long, appletId: String): Action[AnyContent] = Action.async { request =>
    installAppletService
      .execute(RequestUser.id(request), teamId, appletId)
      .map(installed => Ok(Json.toJson(InstalledAppletResDto.from(installed))))
  }

  def uninstall(teamId: Long, appletId: String): Action[AnyContent] = Action.async { request =>
    uninstallAppletService.execute(RequestUser.id(request), teamId, appletId).map(_ => NoContent)
  }
