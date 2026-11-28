package com.cowork.applet.domain.function.model

import com.cowork.applet.global.error.ExpectedException
import play.api.http.Status.BAD_REQUEST
import play.api.libs.json.{JsNull, JsObject, JsValue, Reads}

final case class FunctionArguments(values: JsObject):
  def required[T: Reads](name: String): T =
    values.value.get(name).map(read[T]).getOrElse(throw FunctionArguments.invalid)

  def optional[T: Reads](name: String): Option[T] =
    values.value.get(name).filterNot(_ == JsNull).map(read[T])

  def requiredJson(name: String): JsValue = values.value.getOrElse(name, throw FunctionArguments.invalid)

  private def read[T: Reads](value: JsValue): T = value.validate[T].getOrElse(throw FunctionArguments.invalid)

object FunctionArguments:
  private def invalid = ExpectedException(BAD_REQUEST, "함수 인자가 올바르지 않습니다.")
