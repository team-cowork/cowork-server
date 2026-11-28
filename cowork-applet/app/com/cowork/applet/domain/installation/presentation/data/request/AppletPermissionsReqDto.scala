package com.cowork.applet.domain.installation.presentation.data.request

import play.api.libs.json.{Json, Reads}

// permissions are the app permissions that the team grants. Each must be requested by the published version.
final case class AppletPermissionsReqDto(permissions: Seq[String])

object AppletPermissionsReqDto:
  given Reads[AppletPermissionsReqDto] = Json.reads[AppletPermissionsReqDto]
