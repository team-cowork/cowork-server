package com.cowork.applet.domain.placeholder.presentation.data.response

import play.api.libs.json.{Json, OWrites}

final case class RandomValueResDto(value: String)

object RandomValueResDto:
  given OWrites[RandomValueResDto] = Json.writes[RandomValueResDto]
