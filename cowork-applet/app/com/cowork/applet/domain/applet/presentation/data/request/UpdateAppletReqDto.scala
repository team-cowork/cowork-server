package com.cowork.applet.domain.applet.presentation.data.request

import play.api.libs.json.{Json, Reads}

final case class UpdateAppletReqDto(name: Option[String], description: Option[String])

object UpdateAppletReqDto:
  given Reads[UpdateAppletReqDto] = Json.reads[UpdateAppletReqDto]
