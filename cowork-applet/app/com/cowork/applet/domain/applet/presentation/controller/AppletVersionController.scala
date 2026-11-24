package com.cowork.applet.domain.applet.presentation.controller

import com.cowork.applet.domain.applet.model.{BundleFile, FileEncoding}
import com.cowork.applet.domain.applet.presentation.data.request.{PublishAppletVersionReqDto, SubmitAppletVersionReqDto}
import com.cowork.applet.domain.applet.presentation.data.response.{AppletResDto, AppletVersionResDto}
import com.cowork.applet.domain.applet.service.{
  PublishAppletVersionService,
  QueryAppletVersionService,
  QueryAppletVersionsService,
  SubmitAppletVersionService,
  UnpublishAppletService
}
import com.cowork.applet.global.auth.RequestUser
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.global.json.RequestBodies
import javax.inject.Inject
import play.api.http.Status.BAD_REQUEST
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.concurrent.ExecutionContext

class AppletVersionController @Inject() (
    components: ControllerComponents,
    submitAppletVersionService: SubmitAppletVersionService,
    queryAppletVersionsService: QueryAppletVersionsService,
    queryAppletVersionService: QueryAppletVersionService,
    publishAppletVersionService: PublishAppletVersionService,
    unpublishAppletService: UnpublishAppletService
)(using ExecutionContext)
    extends AbstractController(components):

  // JSON escaping and base64 assets make the body larger than the bundle limits that the review enforces.
  private val MaxBodyBytes = 8L * 1024 * 1024

  def submit(appletId: String): Action[JsValue] = Action.async(parse.json(MaxBodyBytes)) { request =>
    val body = RequestBodies.read[SubmitAppletVersionReqDto](request.body)
    val files = body.files.map { file =>
      val encoding = file.encoding.fold(Some(FileEncoding.UTF8))(FileEncoding.parse)
      BundleFile(file.path, file.content, encoding.getOrElse(throw ExpectedException(BAD_REQUEST, "파일 인코딩은 utf8 또는 base64로 입력하십시오.")))
    }
    submitAppletVersionService
      .execute(RequestUser.id(request), appletId, files)
      .map(version => Created(Json.toJson(AppletVersionResDto.from(version))))
  }

  def list(appletId: String): Action[AnyContent] = Action.async { request =>
    queryAppletVersionsService
      .execute(RequestUser.id(request), appletId)
      .map(versions => Ok(Json.toJson(versions.map(AppletVersionResDto.from))))
  }

  def detail(appletId: String, versionId: String): Action[AnyContent] = Action.async { request =>
    queryAppletVersionService
      .execute(RequestUser.id(request), appletId, versionId)
      .map(version => Ok(Json.toJson(AppletVersionResDto.from(version))))
  }

  def publish(appletId: String): Action[JsValue] = Action.async(parse.json) { request =>
    val body = RequestBodies.read[PublishAppletVersionReqDto](request.body)
    publishAppletVersionService
      .execute(RequestUser.id(request), appletId, body.versionId)
      .map(applet => Ok(Json.toJson(AppletResDto.from(applet))))
  }

  def unpublish(appletId: String): Action[AnyContent] = Action.async { request =>
    unpublishAppletService.execute(RequestUser.id(request), appletId).map(applet => Ok(Json.toJson(AppletResDto.from(applet))))
  }
