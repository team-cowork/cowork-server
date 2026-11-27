package com.cowork.applet.domain.applet.presentation.controller

import com.cowork.applet.domain.applet.presentation.data.request.{CreateAppletReqDto, UpdateAppletReqDto}
import com.cowork.applet.domain.applet.presentation.data.response.{AppletDetailResDto, AppletLaunchResDto, AppletResDto}
import com.cowork.applet.domain.applet.service.{
  CreateAppletService,
  DeleteAppletService,
  LaunchAppletService,
  QueryAppletService,
  QueryMyAppletsService,
  UpdateAppletService
}
import com.cowork.applet.global.auth.RequestUser
import com.cowork.applet.global.json.RequestBodies
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext

class AppletController @Inject() (
    components: ControllerComponents,
    createAppletService: CreateAppletService,
    queryMyAppletsService: QueryMyAppletsService,
    queryAppletService: QueryAppletService,
    updateAppletService: UpdateAppletService,
    deleteAppletService: DeleteAppletService,
    launchAppletService: LaunchAppletService
)(using ExecutionContext)
    extends AbstractController(components):

  def create: Action[JsValue] = Action.async(parse.json) { request =>
    val body = RequestBodies.read[CreateAppletReqDto](request.body)
    createAppletService
      .execute(RequestUser.id(request), body.name, body.description)
      .map(applet => Created(Json.toJson(AppletResDto.from(applet))))
  }

  def mine: Action[AnyContent] = Action.async { request =>
    queryMyAppletsService.execute(RequestUser.id(request)).map(applets => Ok(Json.toJson(applets.map(AppletResDto.from))))
  }

  def detail(appletId: String): Action[AnyContent] = Action.async { request =>
    queryAppletService.execute(RequestUser.id(request), appletId).map(detail => Ok(Json.toJson(AppletDetailResDto.from(detail))))
  }

  def update(appletId: String): Action[JsValue] = Action.async(parse.json) { request =>
    val body = RequestBodies.read[UpdateAppletReqDto](request.body)
    updateAppletService
      .execute(RequestUser.id(request), appletId, body.name, body.description)
      .map(applet => Ok(Json.toJson(AppletResDto.from(applet))))
  }

  def delete(appletId: String): Action[AnyContent] = Action.async { request =>
    deleteAppletService.execute(RequestUser.id(request), appletId).map(_ => NoContent)
  }

  def launch(teamId: Long, appletId: String): Action[AnyContent] = Action.async { request =>
    launchAppletService
      .execute(RequestUser.id(request), teamId, appletId)
      .map(launch => Ok(Json.toJson(AppletLaunchResDto.from(launch))))
  }
