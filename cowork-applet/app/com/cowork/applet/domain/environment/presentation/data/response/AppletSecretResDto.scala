package com.cowork.applet.domain.environment.presentation.data.response

import com.cowork.applet.domain.environment.model.AppletSecret
import java.time.Instant
import play.api.libs.json.{Json, OWrites}

// Secret values are write-only. Responses expose the name and the last change time.
final case class AppletSecretResDto(name: String, updatedAt: Instant)

object AppletSecretResDto:
  given OWrites[AppletSecretResDto] = Json.writes[AppletSecretResDto]

  def from(secret: AppletSecret): AppletSecretResDto = AppletSecretResDto(secret.name, secret.updatedAt)
