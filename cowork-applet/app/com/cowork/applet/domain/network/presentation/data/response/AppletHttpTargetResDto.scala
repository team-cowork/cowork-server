package com.cowork.applet.domain.network.presentation.data.response

import com.cowork.applet.domain.network.model.AppletHttpTarget
import java.time.Instant
import play.api.libs.json.{Json, OWrites}

final case class HttpTargetEntryResDto(name: String, value: String)

object HttpTargetEntryResDto:
  given OWrites[HttpTargetEntryResDto] = Json.writes[HttpTargetEntryResDto]

// Values contain {{secrets.NAME}} references, never decrypted secrets.
final case class AppletHttpTargetResDto(
    host: String,
    headers: Seq[HttpTargetEntryResDto],
    query: Seq[HttpTargetEntryResDto],
    updatedAt: Instant
)

object AppletHttpTargetResDto:
  given OWrites[AppletHttpTargetResDto] = Json.writes[AppletHttpTargetResDto]

  def from(target: AppletHttpTarget): AppletHttpTargetResDto = AppletHttpTargetResDto(
    target.host,
    entries(target.headers),
    entries(target.query),
    target.updatedAt
  )

  private def entries(values: Map[String, String]): Seq[HttpTargetEntryResDto] =
    values.toSeq.sortBy(_._1).map((name, value) => HttpTargetEntryResDto(name, value))
