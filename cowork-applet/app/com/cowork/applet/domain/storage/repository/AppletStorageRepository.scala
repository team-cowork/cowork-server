package com.cowork.applet.domain.storage.repository

import com.cowork.applet.domain.storage.model.{StorageEntry, StorageScope}
import com.cowork.applet.domain.storage.repository.impl.SurrealAppletStorageRepository
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealAppletStorageRepository])
trait AppletStorageRepository:
  def find(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, key: String): Future[Option[StorageEntry]]
  def list(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, prefix: String, limit: Int): Future[Seq[StorageEntry]]
  def count(appletId: String, teamId: Long): Future[Int]
  def countOwned(appletId: String, teamId: Long, scope: StorageScope, ownerId: String): Future[Int]
  def upsert(entry: StorageEntry): Future[Unit]
  def delete(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, key: String): Future[Boolean]
