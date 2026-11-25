package com.cowork.applet.domain.function.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.function.model.{FunctionArguments, InvocationContext}
import com.cowork.applet.domain.function.service.{InvokePlatformFunctionService, PlatformFunctionRegistry}
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.{JsObject, JsValue}
import scala.concurrent.{ExecutionContext, Future}

// The applet runs on the client, so any user who can run it can also call these functions directly.
// Functions must treat their arguments as untrusted input.
@Singleton
class InvokePlatformFunctionServiceImpl @Inject() (
    appletReader: AppletReader,
    platformFunctionRegistry: PlatformFunctionRegistry
)(using ExecutionContext)
    extends InvokePlatformFunctionService:

  override def execute(userId: String, appletId: String, functionName: String, arguments: JsObject): Future[JsValue] =
    platformFunctionRegistry.find(functionName) match
      case None => Future.failed(ExpectedException(NOT_FOUND, "플랫폼 함수를 찾을 수 없습니다."))
      case Some(handler) =>
        appletReader
          .visible(appletId, userId)
          .flatMap(applet => handler.invoke(InvocationContext(applet.appletId, userId), FunctionArguments(arguments)))
