package com.cowork.applet.domain.environment.presentation.data.response

import com.cowork.applet.domain.environment.model.AppletVariable
import java.time.Instant
import play.api.libs.json.{Json, OWrites}

final case class AppletVariableResDto(name: String, value: String, updatedAt: Instant)

object AppletVariableResDto:
  given OWrites[AppletVariableResDto] = Json.writes[AppletVariableResDto]

  def from(variable: AppletVariable): AppletVariableResDto =
    AppletVariableResDto(variable.name, variable.value, variable.updatedAt)
