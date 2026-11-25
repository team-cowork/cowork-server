package com.cowork.applet.domain.applet.presentation.data.request

import play.api.libs.json.{Json, Reads}

final case class CreateAppletReqDto(name: String, description: Option[String])

object CreateAppletReqDto:
  given Reads[CreateAppletReqDto] = Json.reads[CreateAppletReqDto]
