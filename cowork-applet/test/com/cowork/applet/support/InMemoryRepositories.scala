package com.cowork.applet.support

import com.cowork.applet.domain.applet.model.{Applet, AppletVersion, AppletVersionStatus}
import com.cowork.applet.domain.applet.repository.{AppletRepository, AppletVersionRepository}
import com.cowork.applet.domain.storage.model.{StorageEntry, StorageScope}
import com.cowork.applet.domain.storage.repository.AppletStorageRepository
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.Future

final class InMemoryAppletRepository(initial: Applet*) extends AppletRepository:
  val applets: mutable.Map[String, Applet] = mutable.Map.from(initial.map(a => a.appletId -> a))

  override def create(applet: Applet): Future[Unit] = Future.successful(applets.update(applet.appletId, applet))
  override def findById(appletId: String): Future[Option[Applet]] = Future.successful(applets.get(appletId))
  override def findByOwner(ownerId: String): Future[Seq[Applet]] =
    Future.successful(applets.values.filter(_.ownerId == ownerId).toSeq)
  override def updateProfile(applet: Applet): Future[Unit] = Future.successful(applets.update(applet.appletId, applet))
  override def updatePublishedVersion(appletId: String, versionId: Option[String], updatedAt: Instant): Future[Unit] =
    Future.successful(applets.updateWith(appletId)(_.map(_.copy(publishedVersionId = versionId, updatedAt = updatedAt))))
  override def deleteWithResources(appletId: String): Future[Unit] = Future.successful(applets.remove(appletId))

final class InMemoryAppletVersionRepository(appletRepository: InMemoryAppletRepository) extends AppletVersionRepository:
  val versions: mutable.ListBuffer[AppletVersion] = mutable.ListBuffer.empty

  override def nextNumber(appletId: String): Future[Int] =
    Future.successful(versions.filter(_.appletId == appletId).map(_.number).maxOption.getOrElse(0) + 1)
  override def create(version: AppletVersion): Future[Unit] = Future.successful(versions += version)
  override def createAndPublish(version: AppletVersion, publishedAt: Instant): Future[Unit] =
    versions += version
    appletRepository.applets.updateWith(version.appletId)(
      _.map(_.copy(publishedVersionId = Some(version.versionId), updatedAt = publishedAt))
    )
    Future.successful(())
  override def findSummaries(appletId: String): Future[Seq[AppletVersion]] =
    Future.successful(versions.filter(_.appletId == appletId).map(_.copy(files = Nil)).toSeq)
  override def findSummary(appletId: String, versionId: String): Future[Option[AppletVersion]] =
    Future.successful(versions.find(v => v.appletId == appletId && v.versionId == versionId).map(_.copy(files = Nil)))
  override def findWithFiles(appletId: String, versionId: String): Future[Option[AppletVersion]] =
    Future.successful(versions.find(v => v.appletId == appletId && v.versionId == versionId))
  override def archive(appletId: String, versionIds: Seq[String]): Future[Unit] =
    versions.mapInPlace(v =>
      if v.appletId == appletId && versionIds.contains(v.versionId) then
        v.copy(status = AppletVersionStatus.ARCHIVED, files = Nil)
      else v
    )
    Future.successful(())
  override def delete(appletId: String, versionIds: Seq[String]): Future[Unit] =
    versions.filterInPlace(v => !(v.appletId == appletId && versionIds.contains(v.versionId)))
    Future.successful(())

final class InMemoryAppletStorageRepository extends AppletStorageRepository:
  val entries: mutable.ListBuffer[StorageEntry] = mutable.ListBuffer.empty

  private def matches(e: StorageEntry, appletId: String, scope: StorageScope, ownerId: String) =
    e.appletId == appletId && e.scope == scope && e.ownerId == ownerId

  override def find(appletId: String, scope: StorageScope, ownerId: String, key: String): Future[Option[StorageEntry]] =
    Future.successful(entries.find(e => matches(e, appletId, scope, ownerId) && e.key == key))
  override def list(appletId: String, scope: StorageScope, ownerId: String, prefix: String, limit: Int): Future[Seq[StorageEntry]] =
    Future.successful(entries.filter(e => matches(e, appletId, scope, ownerId) && e.key.startsWith(prefix)).sortBy(_.key).take(limit).toSeq)
  override def count(appletId: String): Future[Int] = Future.successful(entries.count(_.appletId == appletId))
  override def upsert(entry: StorageEntry): Future[Unit] =
    entries.filterInPlace(e => !(matches(e, entry.appletId, entry.scope, entry.ownerId) && e.key == entry.key))
    entries += entry
    Future.successful(())
  override def delete(appletId: String, scope: StorageScope, ownerId: String, key: String): Future[Boolean] =
    val before = entries.size
    entries.filterInPlace(e => !(matches(e, appletId, scope, ownerId) && e.key == key))
    Future.successful(entries.size != before)

object Fixtures:
  val Owner = "1"
  val Other = "2"

  def applet(published: Boolean = false, appletId: String = "applet-1"): Applet =
    val now = Instant.parse("2026-11-24T00:00:00Z")
    Applet(appletId, Owner, "앱", "", if published then Some("version-1") else None, now, now)
