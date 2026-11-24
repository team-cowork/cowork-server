package com.cowork.applet.domain.function.service.handler

import com.cowork.applet.domain.function.model.{FunctionArguments, InvocationContext, PlatformFunction}
import play.api.libs.json.JsValue
import scala.concurrent.Future

// The catalog comes from the registered handlers, so a listed function always has an implementation.
trait PlatformFunctionHandler:
  def definition: PlatformFunction
  def invoke(context: InvocationContext, arguments: FunctionArguments): Future[JsValue]
