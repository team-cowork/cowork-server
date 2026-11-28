package com.cowork.applet.domain.applet.presentation.data.response

import com.cowork.applet.domain.applet.model.AppletDetail
import java.time.Instant
import play.api.libs.json.{Json, OWrites}

final case class AppletDetailResDto(
    appletId: String,
    ownerId: String,
    name: String,
    description: String,
    publishedVersionId: Option[String],
    permissions: Seq[String],
    createdAt: Instant,
    updatedAt: Instant
)

object AppletDetailResDto:
  given OWrites[AppletDetailResDto] = Json.writes[AppletDetailResDto]

  def from(detail: AppletDetail): AppletDetailResDto = AppletDetailResDto(
    detail.applet.appletId,
    detail.applet.ownerId,
    detail.applet.name,
    detail.applet.description,
    detail.applet.publishedVersionId,
    detail.permissions,
    detail.applet.createdAt,
    detail.applet.updatedAt
  )
