package com.cowork.applet.domain.function.service

import com.cowork.applet.domain.function.service.impl.InvokePlatformFunctionServiceImpl
import com.google.inject.ImplementedBy
import play.api.libs.json.{JsObject, JsValue}
import scala.concurrent.Future

@ImplementedBy(classOf[InvokePlatformFunctionServiceImpl])
trait InvokePlatformFunctionService:
  def execute(userId: String, teamId: Long, appletId: String, functionName: String, arguments: JsObject): Future[JsValue]
