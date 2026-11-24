package com.cowork.applet.domain.installation.repository

import com.cowork.applet.domain.installation.model.AppletInstallation
import com.cowork.applet.domain.installation.repository.impl.SurrealAppletInstallationRepository
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealAppletInstallationRepository])
trait AppletInstallationRepository:
  def findByTeam(teamId: Long): Future[Seq[AppletInstallation]]
  // Creates the installation unless the team already has the applet.
  def create(installation: AppletInstallation): Future[Unit]
  def delete(teamId: Long, appletId: String): Future[Boolean]
