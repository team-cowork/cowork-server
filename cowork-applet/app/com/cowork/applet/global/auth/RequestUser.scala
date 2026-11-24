package com.cowork.applet.global.auth

import com.cowork.applet.global.error.ExpectedException
import play.api.http.Status.UNAUTHORIZED
import play.api.mvc.RequestHeader

// Gateway validates the access JWT, removes client-supplied identity headers, and sets X-User-Id.
object RequestUser:
  private val Header = "X-User-Id"

  def id(request: RequestHeader): String =
    request.headers
      .get(Header)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(throw ExpectedException(UNAUTHORIZED, "인증이 필요합니다."))
