package com.cowork.applet.domain.environment.presentation.data.request

import play.api.libs.json.{Json, Reads}

final case class PutEnvironmentValueReqDto(value: String)

object PutEnvironmentValueReqDto:
  given Reads[PutEnvironmentValueReqDto] = Json.reads[PutEnvironmentValueReqDto]
