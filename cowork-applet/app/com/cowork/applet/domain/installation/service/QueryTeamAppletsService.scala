package com.cowork.applet.domain.installation.service

import com.cowork.applet.domain.installation.model.InstalledApplet
import com.cowork.applet.domain.installation.service.impl.QueryTeamAppletsServiceImpl
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[QueryTeamAppletsServiceImpl])
trait QueryTeamAppletsService:
  def execute(userId: String, teamId: Long): Future[Seq[InstalledApplet]]
