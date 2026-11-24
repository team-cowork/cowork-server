package com.cowork.applet.domain.installation.model

import com.cowork.applet.domain.applet.model.Applet
import java.time.Instant
import play.api.libs.json.{Json, OFormat}

// teamId refers to the team ID owned by cowork-team.
final case class AppletInstallation(teamId: Long, appletId: String, installedBy: String, installedAt: Instant)

object AppletInstallation:
  given OFormat[AppletInstallation] = Json.format[AppletInstallation]

  val MaxPerTeam = 50

final case class InstalledApplet(installation: AppletInstallation, applet: Applet)
