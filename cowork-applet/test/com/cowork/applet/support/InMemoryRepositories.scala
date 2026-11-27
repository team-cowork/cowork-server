package com.cowork.applet.support

import com.cowork.applet.domain.applet.model.{Applet, AppletVersion, AppletVersionStatus}
import com.cowork.applet.domain.applet.repository.{AppletRepository, AppletVersionRepository}
import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.repository.AppletSecretRepository
import com.cowork.applet.domain.installation.model.AppletInstallation
import com.cowork.applet.domain.installation.repository.AppletInstallationRepository
import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.repository.AppletHttpTargetRepository
import com.cowork.applet.domain.storage.model.{StorageEntry, StorageScope}
import com.cowork.applet.domain.storage.repository.AppletStorageRepository
import com.cowork.applet.domain.team.model.TeamRolePolicy
import com.cowork.applet.domain.team.projection.TeamProjectionReadiness
import com.cowork.applet.domain.team.repository.TeamProjectionRepository
import com.cowork.applet.global.outbound.{OutboundHttpClient, OutboundRequest, OutboundResponse}
import java.time.Instant
import play.api.libs.json.Json
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

  private def matches(e: StorageEntry, appletId: String, teamId: Long, scope: StorageScope, ownerId: String) =
    e.appletId == appletId && e.teamId == teamId && e.scope == scope && e.ownerId == ownerId

  override def find(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, key: String): Future[Option[StorageEntry]] =
    Future.successful(entries.find(e => matches(e, appletId, teamId, scope, ownerId) && e.key == key))
  override def list(
      appletId: String,
      teamId: Long,
      scope: StorageScope,
      ownerId: String,
      prefix: String,
      limit: Int
  ): Future[Seq[StorageEntry]] =
    Future.successful(
      entries.filter(e => matches(e, appletId, teamId, scope, ownerId) && e.key.startsWith(prefix)).sortBy(_.key).take(limit).toSeq
    )
  override def count(appletId: String, teamId: Long): Future[Int] =
    Future.successful(entries.count(e => e.appletId == appletId && e.teamId == teamId))
  override def countOwned(appletId: String, teamId: Long, scope: StorageScope, ownerId: String): Future[Int] =
    Future.successful(entries.count(e => matches(e, appletId, teamId, scope, ownerId)))
  override def upsert(entry: StorageEntry): Future[Unit] =
    entries.filterInPlace(e => !(matches(e, entry.appletId, entry.teamId, entry.scope, entry.ownerId) && e.key == entry.key))
    entries += entry
    Future.successful(())
  override def delete(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, key: String): Future[Boolean] =
    val before = entries.size
    entries.filterInPlace(e => !(matches(e, appletId, teamId, scope, ownerId) && e.key == key))
    Future.successful(entries.size != before)

object Fixtures:
  val Owner = "1"
  val Other = "2"
  val TeamA = 3L
  val TeamB = 4L

  // Every member of TeamA and TeamB may use applets through the default role, except the members of NoUseTeam.
  val NoUseTeam = 5L

  def appletUsers(members: Set[(Long, Long)]): InMemoryTeamProjectionRepository =
    val policies = Seq(TeamA, TeamB).map(team =>
      TeamRolePolicy(s"role:$team:1", team, "ROLE", Some(1), None, true, Json.obj("use_applets" -> true), false, 1)
    )
    InMemoryTeamProjectionRepository(members, policies)

  def version(permissions: Seq[String] = Nil, versionId: String = "version-1"): AppletVersion =
    AppletVersion(versionId, "applet-1", 1, AppletVersionStatus.APPROVED, Nil, Nil, Instant.parse("2026-11-24T00:00:00Z"), permissions)

  def applet(published: Boolean = false, appletId: String = "applet-1"): Applet =
    val now = Instant.parse("2026-11-24T00:00:00Z")
    Applet(appletId, Owner, "앱", "", if published then Some("version-1") else None, now, now)

final class InMemoryAppletSecretRepository extends AppletSecretRepository:
  val secrets: mutable.ListBuffer[AppletSecret] = mutable.ListBuffer.empty

  override def findAll(appletId: String): Future[Seq[AppletSecret]] = Future.successful(secrets.filter(_.appletId == appletId).toSeq)
  override def upsert(secret: AppletSecret): Future[Unit] =
    secrets.filterInPlace(s => !(s.appletId == secret.appletId && s.name == secret.name))
    secrets += secret
    Future.successful(())
  override def delete(appletId: String, name: String): Future[Boolean] =
    val before = secrets.size
    secrets.filterInPlace(s => !(s.appletId == appletId && s.name == name))
    Future.successful(secrets.size != before)

final class InMemoryAppletHttpTargetRepository(initial: AppletHttpTarget*) extends AppletHttpTargetRepository:
  val targets: mutable.ListBuffer[AppletHttpTarget] = mutable.ListBuffer.from(initial)

  override def findAll(appletId: String): Future[Seq[AppletHttpTarget]] = Future.successful(targets.filter(_.appletId == appletId).toSeq)
  override def find(appletId: String, host: String): Future[Option[AppletHttpTarget]] =
    Future.successful(targets.find(t => t.appletId == appletId && t.host == host))
  override def upsert(target: AppletHttpTarget): Future[Unit] =
    targets.filterInPlace(t => !(t.appletId == target.appletId && t.host == target.host))
    targets += target
    Future.successful(())
  override def delete(appletId: String, host: String): Future[Boolean] =
    val before = targets.size
    targets.filterInPlace(t => !(t.appletId == appletId && t.host == host))
    Future.successful(targets.size != before)

// Records requests and answers with a fixed result instead of calling the network.
final class FakeOutboundHttpClient(answer: OutboundRequest => Future[OutboundResponse]) extends OutboundHttpClient:
  val requests: mutable.ListBuffer[OutboundRequest] = mutable.ListBuffer.empty

  override def execute(request: OutboundRequest): Future[OutboundResponse] =
    requests += request
    answer(request)

final class InMemoryTeamProjectionRepository(
    activeMembers: Set[(Long, Long)],
    policies: Seq[TeamRolePolicy]
) extends TeamProjectionRepository:
  override def isActiveMember(teamId: Long, userId: Long): Future[Boolean] = Future.successful(activeMembers.contains(teamId -> userId))
  override def policiesFor(teamId: Long, userId: Long): Future[Seq[TeamRolePolicy]] =
    Future.successful(policies.filter(p => p.teamId == teamId && (p.kind == "ROLE" || p.accountId.contains(userId))))

final class FixedTeamProjectionReadiness(ready: Boolean) extends TeamProjectionReadiness:
  override def membersReady: Boolean = ready
  override def policiesReady: Boolean = ready

final class InMemoryAppletInstallationRepository extends AppletInstallationRepository:
  val installations: mutable.ListBuffer[AppletInstallation] = mutable.ListBuffer.empty

  override def findByTeam(teamId: Long): Future[Seq[AppletInstallation]] =
    Future.successful(installations.filter(_.teamId == teamId).toSeq)
  override def find(teamId: Long, appletId: String): Future[Option[AppletInstallation]] =
    Future.successful(installations.find(i => i.teamId == teamId && i.appletId == appletId))
  override def updateGrantedPermissions(teamId: Long, appletId: String, permissions: Seq[String]): Future[Boolean] =
    val index = installations.indexWhere(i => i.teamId == teamId && i.appletId == appletId)
    if index >= 0 then installations.update(index, installations(index).copy(grantedPermissions = permissions))
    Future.successful(index >= 0)
  override def create(installation: AppletInstallation): Future[Unit] =
    if !installations.exists(i => i.teamId == installation.teamId && i.appletId == installation.appletId) then installations += installation
    Future.successful(())
  override def delete(teamId: Long, appletId: String): Future[Boolean] =
    val before = installations.size
    installations.filterInPlace(i => !(i.teamId == teamId && i.appletId == appletId))
    Future.successful(installations.size != before)
