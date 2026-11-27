package com.cowork.applet.domain.installation.model

import com.cowork.applet.domain.applet.model.Applet
import java.time.Instant
import play.api.libs.json.{Json, OFormat}

// teamId refers to the team ID owned by cowork-team.
// grantedPermissions are the app permissions that the team approved. Like a GitHub App, a newer version can request
// more, and the applet can use only the permissions that are both granted here and requested by the published version.
final case class AppletInstallation(
    teamId: Long,
    appletId: String,
    installedBy: String,
    installedAt: Instant,
    grantedPermissions: Seq[String] = Nil
):
  def allows(permission: String, requested: Seq[String]): Boolean =
    grantedPermissions.contains(permission) && requested.contains(permission)

object AppletInstallation:
  // Installations stored before permissions existed grant none.
  given OFormat[AppletInstallation] = Json.using[Json.WithDefaultValues].format[AppletInstallation]

  val MaxPerTeam = 50

final case class InstalledApplet(installation: AppletInstallation, applet: Applet)
