package com.cowork.applet.domain.network.presentation.controller

import com.cowork.applet.domain.network.presentation.data.request.PutAppletHttpTargetReqDto
import com.cowork.applet.domain.network.presentation.data.response.AppletHttpTargetResDto
import com.cowork.applet.domain.network.service.{
  DeleteAppletHttpTargetService,
  PutAppletHttpTargetService,
  QueryAppletHttpTargetsService
}
import com.cowork.applet.global.auth.RequestUser
import com.cowork.applet.global.json.RequestBodies
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext

class AppletHttpTargetController @Inject() (
    components: ControllerComponents,
    queryAppletHttpTargetsService: QueryAppletHttpTargetsService,
    putAppletHttpTargetService: PutAppletHttpTargetService,
    deleteAppletHttpTargetService: DeleteAppletHttpTargetService
)(using ExecutionContext)
    extends AbstractController(components):

  def list(appletId: String): Action[AnyContent] = Action.async { request =>
    queryAppletHttpTargetsService
      .execute(RequestUser.id(request), appletId)
      .map(targets => Ok(Json.toJson(targets.map(AppletHttpTargetResDto.from))))
  }

  def put(appletId: String, host: String): Action[JsValue] = Action.async(parse.json) { request =>
    val body = RequestBodies.read[PutAppletHttpTargetReqDto](request.body)
    putAppletHttpTargetService
      .execute(RequestUser.id(request), appletId, host, body.headers.getOrElse(Map.empty), body.query.getOrElse(Map.empty))
      .map(target => Ok(Json.toJson(AppletHttpTargetResDto.from(target))))
  }

  def delete(appletId: String, host: String): Action[AnyContent] = Action.async { request =>
    deleteAppletHttpTargetService.execute(RequestUser.id(request), appletId, host).map(_ => NoContent)
  }
