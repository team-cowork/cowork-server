package com.cowork.applet.domain.storage.repository.impl

import com.cowork.applet.domain.storage.model.{StorageEntry, StorageScope}
import com.cowork.applet.domain.storage.repository.AppletStorageRepository
import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, JsObject, Json}
import scala.concurrent.{ExecutionContext, Future}

// `key` and `value` are escaped because SurrealQL also uses them as keywords.
@Singleton
class SurrealAppletStorageRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext)
    extends AppletStorageRepository:

  private val Match = "appletId = $appletId AND teamId = $teamId AND scope = $scope AND ownerId = $ownerId AND `key` = $key"

  override def find(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, key: String): Future[Option[StorageEntry]] =
    surrealClient
      .query(s"SELECT * OMIT id FROM applet_storage WHERE $Match LIMIT 1;", matchVariables(appletId, teamId, scope, ownerId, key))
      .map(results => results.head.as[Seq[StorageEntry]].headOption)

  override def list(
      appletId: String,
      teamId: Long,
      scope: StorageScope,
      ownerId: String,
      prefix: String,
      limit: Int
  ): Future[Seq[StorageEntry]] =
    val statement =
      s"""SELECT * OMIT id FROM applet_storage
         |WHERE appletId = $$appletId AND teamId = $$teamId AND scope = $$scope AND ownerId = $$ownerId
         |  AND string::starts_with(`key`, $$prefix)
         |ORDER BY `key` LIMIT ${limit.max(1)};""".stripMargin
    val variables = Json.obj("appletId" -> appletId, "teamId" -> teamId, "scope" -> scope, "ownerId" -> ownerId, "prefix" -> prefix)
    surrealClient.query(statement, variables).map(results => results.head.as[Seq[StorageEntry]])

  override def count(appletId: String, teamId: Long): Future[Int] =
    surrealClient
      .query(
        "SELECT count() AS total FROM applet_storage WHERE appletId = $appletId AND teamId = $teamId GROUP ALL;",
        Json.obj("appletId" -> appletId, "teamId" -> teamId)
      )
      .map(results => results.head.as[Seq[JsObject]].headOption.flatMap(row => (row \ "total").asOpt[Int]).getOrElse(0))

  override def countOwned(appletId: String, teamId: Long, scope: StorageScope, ownerId: String): Future[Int] =
    surrealClient
      .query(
        """SELECT count() AS total FROM applet_storage
          |WHERE appletId = $appletId AND teamId = $teamId AND scope = $scope AND ownerId = $ownerId GROUP ALL;""".stripMargin,
        Json.obj("appletId" -> appletId, "teamId" -> teamId, "scope" -> scope, "ownerId" -> ownerId)
      )
      .map(results => results.head.as[Seq[JsObject]].headOption.flatMap(row => (row \ "total").asOpt[Int]).getOrElse(0))

  override def upsert(entry: StorageEntry): Future[Unit] =
    val statements =
      """LET $updated = (UPDATE applet_storage SET `value` = $entry.`value`, updatedAt = $entry.updatedAt
        |  WHERE appletId = $entry.appletId AND teamId = $entry.teamId AND scope = $entry.scope
        |    AND ownerId = $entry.ownerId AND `key` = $entry.`key`);
        |IF array::len($updated) == 0 { CREATE applet_storage CONTENT $entry RETURN NONE; };""".stripMargin
    surrealClient.query(statements, Json.obj("entry" -> Json.toJsObject(entry))).map(_ => ())

  override def delete(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, key: String): Future[Boolean] =
    surrealClient
      .query(s"DELETE applet_storage WHERE $Match RETURN BEFORE;", matchVariables(appletId, teamId, scope, ownerId, key))
      .map(results => results.head.as[JsArray].value.nonEmpty)

  private def matchVariables(appletId: String, teamId: Long, scope: StorageScope, ownerId: String, key: String): JsObject =
    Json.obj("appletId" -> appletId, "teamId" -> teamId, "scope" -> scope, "ownerId" -> ownerId, "key" -> key)
