package com.cowork.applet.domain.applet.model

// permissions are the app permissions that the published version requests.
final case class AppletDetail(applet: Applet, permissions: Seq[String])
