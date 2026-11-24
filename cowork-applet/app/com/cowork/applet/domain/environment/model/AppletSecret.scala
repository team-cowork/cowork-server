package com.cowork.applet.domain.environment.model

import java.time.Instant
import play.api.libs.json.{Json, OFormat}

// Secrets never leave the server in plaintext. Server-side platform functions decrypt them when they call
// an external service for the applet.
final case class AppletSecret(appletId: String, name: String, ciphertext: String, updatedAt: Instant)

object AppletSecret:
  given OFormat[AppletSecret] = Json.format[AppletSecret]

  def associatedData(appletId: String, name: String): String = s"applet-secret:$appletId:$name"
