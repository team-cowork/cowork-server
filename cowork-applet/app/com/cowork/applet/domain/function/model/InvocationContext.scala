package com.cowork.applet.domain.function.model

// teamId is the team that installed the applet and runs it.
final case class InvocationContext(appletId: String, teamId: Long, userId: String)
