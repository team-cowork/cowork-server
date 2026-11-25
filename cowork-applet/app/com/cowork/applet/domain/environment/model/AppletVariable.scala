package com.cowork.applet.domain.environment.model

import java.time.Instant
import play.api.libs.json.{Json, OFormat}

// Variables reach every client that runs the applet, so they hold only public values.
final case class AppletVariable(appletId: String, name: String, value: String, updatedAt: Instant)

object AppletVariable:
  given OFormat[AppletVariable] = Json.format[AppletVariable]
