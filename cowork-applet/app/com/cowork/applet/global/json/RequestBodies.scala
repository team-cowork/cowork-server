package com.cowork.applet.global.json

import com.cowork.applet.global.error.ExpectedException
import play.api.http.Status.BAD_REQUEST
import play.api.libs.json.{JsValue, Reads}

object RequestBodies:
  def read[T: Reads](body: JsValue): T =
    body.validate[T].getOrElse(throw ExpectedException(BAD_REQUEST, "요청 본문 형식이 올바르지 않습니다."))
