package com.cowork.applet.domain.applet.presentation.data.response

import com.cowork.applet.domain.applet.model.Applet
import java.time.Instant
import play.api.libs.json.{Json, OWrites}

final case class AppletResDto(
    appletId: String,
    ownerId: String,
    name: String,
    description: String,
    publishedVersionId: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

object AppletResDto:
  given OWrites[AppletResDto] = Json.writes[AppletResDto]

  def from(applet: Applet): AppletResDto = AppletResDto(
    applet.appletId,
    applet.ownerId,
    applet.name,
    applet.description,
    applet.publishedVersionId,
    applet.createdAt,
    applet.updatedAt
  )
