package com.cowork.applet.domain.installation.presentation.data.response

import com.cowork.applet.domain.applet.presentation.data.response.AppletResDto
import com.cowork.applet.domain.installation.model.InstalledApplet
import java.time.Instant
import play.api.libs.json.{Json, OWrites}

final case class InstalledAppletResDto(teamId: Long, installedBy: String, installedAt: Instant, applet: AppletResDto)

object InstalledAppletResDto:
  given OWrites[InstalledAppletResDto] = Json.writes[InstalledAppletResDto]

  def from(installed: InstalledApplet): InstalledAppletResDto = InstalledAppletResDto(
    installed.installation.teamId,
    installed.installation.installedBy,
    installed.installation.installedAt,
    AppletResDto.from(installed.applet)
  )
