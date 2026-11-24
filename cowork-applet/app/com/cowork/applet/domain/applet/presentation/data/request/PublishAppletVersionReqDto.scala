package com.cowork.applet.domain.applet.presentation.data.request

import play.api.libs.json.{Json, Reads}

final case class PublishAppletVersionReqDto(versionId: String)

object PublishAppletVersionReqDto:
  given Reads[PublishAppletVersionReqDto] = Json.reads[PublishAppletVersionReqDto]
