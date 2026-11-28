package com.cowork.applet.domain.function.presentation.controller

import com.cowork.applet.domain.function.presentation.data.response.PlatformFunctionResDto
import com.cowork.applet.domain.function.service.{InvokePlatformFunctionService, QueryPlatformFunctionsService}
import com.cowork.applet.global.auth.RequestUser
import com.cowork.applet.global.json.RequestBodies
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext

class PlatformFunctionController @Inject() (
    components: ControllerComponents,
    queryPlatformFunctionsService: QueryPlatformFunctionsService,
    invokePlatformFunctionService: InvokePlatformFunctionService
)(using ExecutionContext)
    extends AbstractController(components):

  def catalog: Action[AnyContent] = Action { request =>
    RequestUser.id(request)
    Ok(Json.toJson(queryPlatformFunctionsService.execute().map(PlatformFunctionResDto.from)))
  }

  def invoke(teamId: Long, appletId: String, functionName: String): Action[JsValue] = Action.async(parse.json) { request =>
    val arguments = RequestBodies.read[JsObject](request.body)
    invokePlatformFunctionService
      .execute(RequestUser.id(request), teamId, appletId, functionName, arguments)
      .map(result => Ok(Json.obj("result" -> result)))
  }
