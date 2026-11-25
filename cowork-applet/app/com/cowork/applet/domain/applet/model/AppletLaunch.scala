package com.cowork.applet.domain.applet.model

// Everything a client needs to start the published version inside its sandbox.
final case class AppletLaunch(applet: Applet, version: AppletVersion, environment: Map[String, String])
