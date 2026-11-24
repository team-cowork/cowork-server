package com.cowork.applet.domain.applet.model

import java.time.Instant
import play.api.libs.json.{Json, OFormat}

final case class Applet(
    appletId: String,
    ownerId: String,
    name: String,
    description: String,
    publishedVersionId: Option[String],
    createdAt: Instant,
    updatedAt: Instant
):
  def isOwnedBy(userId: String): Boolean = ownerId == userId

  def isPublished: Boolean = publishedVersionId.isDefined

object Applet:
  given OFormat[Applet] = Json.format[Applet]
