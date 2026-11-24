package com.cowork.applet.domain.applet.repository

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.impl.SurrealAppletRepository
import com.google.inject.ImplementedBy
import java.time.Instant
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealAppletRepository])
trait AppletRepository:
  def create(applet: Applet): Future[Unit]
  def findById(appletId: String): Future[Option[Applet]]
  def findByOwner(ownerId: String): Future[Seq[Applet]]
  def updateProfile(applet: Applet): Future[Unit]
  def updatePublishedVersion(appletId: String, versionId: Option[String], updatedAt: Instant): Future[Unit]
  // Deletes the applet with its versions, variables, secrets, HTTP targets, team installations, and storage entries in one transaction.
  def deleteWithResources(appletId: String): Future[Unit]
