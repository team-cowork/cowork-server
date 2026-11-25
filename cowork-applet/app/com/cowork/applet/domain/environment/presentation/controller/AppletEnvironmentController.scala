package com.cowork.applet.domain.environment.presentation.controller

import com.cowork.applet.domain.environment.presentation.data.request.PutEnvironmentValueReqDto
import com.cowork.applet.domain.environment.presentation.data.response.{AppletSecretResDto, AppletVariableResDto}
import com.cowork.applet.domain.environment.service.{
  DeleteAppletSecretService,
  DeleteAppletVariableService,
  PutAppletSecretService,
  PutAppletVariableService,
  QueryAppletSecretsService,
  QueryAppletVariablesService
}
import com.cowork.applet.global.auth.RequestUser
import com.cowork.applet.global.json.RequestBodies
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext

class AppletEnvironmentController @Inject() (
    components: ControllerComponents,
    queryAppletVariablesService: QueryAppletVariablesService,
    putAppletVariableService: PutAppletVariableService,
    deleteAppletVariableService: DeleteAppletVariableService,
    queryAppletSecretsService: QueryAppletSecretsService,
    putAppletSecretService: PutAppletSecretService,
    deleteAppletSecretService: DeleteAppletSecretService
)(using ExecutionContext)
    extends AbstractController(components):

  def variables(appletId: String): Action[AnyContent] = Action.async { request =>
    queryAppletVariablesService
      .execute(RequestUser.id(request), appletId)
      .map(variables => Ok(Json.toJson(variables.map(AppletVariableResDto.from))))
  }

  def putVariable(appletId: String, name: String): Action[JsValue] = Action.async(parse.json) { request =>
    val body = RequestBodies.read[PutEnvironmentValueReqDto](request.body)
    putAppletVariableService
      .execute(RequestUser.id(request), appletId, name, body.value)
      .map(variable => Ok(Json.toJson(AppletVariableResDto.from(variable))))
  }

  def deleteVariable(appletId: String, name: String): Action[AnyContent] = Action.async { request =>
    deleteAppletVariableService.execute(RequestUser.id(request), appletId, name).map(_ => NoContent)
  }

  def secrets(appletId: String): Action[AnyContent] = Action.async { request =>
    queryAppletSecretsService
      .execute(RequestUser.id(request), appletId)
      .map(secrets => Ok(Json.toJson(secrets.map(AppletSecretResDto.from))))
  }

  def putSecret(appletId: String, name: String): Action[JsValue] = Action.async(parse.json) { request =>
    val body = RequestBodies.read[PutEnvironmentValueReqDto](request.body)
    putAppletSecretService
      .execute(RequestUser.id(request), appletId, name, body.value)
      .map(secret => Ok(Json.toJson(AppletSecretResDto.from(secret))))
  }

  def deleteSecret(appletId: String, name: String): Action[AnyContent] = Action.async { request =>
    deleteAppletSecretService.execute(RequestUser.id(request), appletId, name).map(_ => NoContent)
  }
