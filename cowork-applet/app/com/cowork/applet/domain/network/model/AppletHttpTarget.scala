package com.cowork.applet.domain.network.model

import java.time.Instant
import play.api.libs.json.{Json, OFormat}

// An external HTTPS host that the applet may call through http.fetch. Header and query values may contain
// {{secrets.NAME}} references. Only the owner sets them, so callers cannot choose where a secret is sent.
final case class AppletHttpTarget(
    appletId: String,
    host: String,
    headers: Map[String, String],
    query: Map[String, String],
    updatedAt: Instant
)

object AppletHttpTarget:
  given OFormat[AppletHttpTarget] = Json.format[AppletHttpTarget]
