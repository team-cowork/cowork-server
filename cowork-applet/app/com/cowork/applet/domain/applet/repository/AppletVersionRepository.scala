package com.cowork.applet.domain.applet.repository

import com.cowork.applet.domain.applet.model.AppletVersion
import com.cowork.applet.domain.applet.repository.impl.SurrealAppletVersionRepository
import com.google.inject.ImplementedBy
import java.time.Instant
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealAppletVersionRepository])
trait AppletVersionRepository:
  def nextNumber(appletId: String): Future[Int]
  def create(version: AppletVersion): Future[Unit]
  // Stores the version and points the applet at it in one transaction.
  def createAndPublish(version: AppletVersion, publishedAt: Instant): Future[Unit]
  def findSummaries(appletId: String): Future[Seq[AppletVersion]]
  def findSummary(appletId: String, versionId: String): Future[Option[AppletVersion]]
  def findWithFiles(appletId: String, versionId: String): Future[Option[AppletVersion]]
  // Removes the files of approved versions and marks them ARCHIVED.
  def archive(appletId: String, versionIds: Seq[String]): Future[Unit]
  def delete(appletId: String, versionIds: Seq[String]): Future[Unit]
