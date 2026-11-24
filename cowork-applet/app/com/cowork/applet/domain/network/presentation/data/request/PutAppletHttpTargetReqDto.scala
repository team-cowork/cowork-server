package com.cowork.applet.domain.network.presentation.data.request

import play.api.libs.json.{Json, Reads}

final case class PutAppletHttpTargetReqDto(headers: Option[Map[String, String]], query: Option[Map[String, String]])

object PutAppletHttpTargetReqDto:
  given Reads[PutAppletHttpTargetReqDto] = Json.reads[PutAppletHttpTargetReqDto]
