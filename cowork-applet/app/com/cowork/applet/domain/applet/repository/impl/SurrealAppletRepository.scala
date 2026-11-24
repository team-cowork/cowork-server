package com.cowork.applet.domain.applet.repository.impl

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.global.surreal.SurrealClient
import java.time.Instant
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsObject, Json}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SurrealAppletRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext) extends AppletRepository:

  override def create(applet: Applet): Future[Unit] =
    surrealClient
      .query("CREATE applet CONTENT $applet RETURN NONE;", Json.obj("applet" -> Json.toJsObject(applet)))
      .map(_ => ())

  override def findById(appletId: String): Future[Option[Applet]] =
    surrealClient
      .query("SELECT * OMIT id FROM applet WHERE appletId = $appletId LIMIT 1;", Json.obj("appletId" -> appletId))
      .map(results => results.head.as[Seq[Applet]].headOption)

  override def findByOwner(ownerId: String): Future[Seq[Applet]] =
    surrealClient
      .query("SELECT * OMIT id FROM applet WHERE ownerId = $ownerId ORDER BY createdAt DESC;", Json.obj("ownerId" -> ownerId))
      .map(results => results.head.as[Seq[Applet]])

  override def updateProfile(applet: Applet): Future[Unit] =
    val statement =
      "UPDATE applet SET name = $name, description = $description, updatedAt = $updatedAt WHERE appletId = $appletId RETURN NONE;"
    val variables: JsObject = Json.obj(
      "appletId" -> applet.appletId,
      "name" -> applet.name,
      "description" -> applet.description,
      "updatedAt" -> applet.updatedAt
    )
    surrealClient.query(statement, variables).map(_ => ())

  override def updatePublishedVersion(appletId: String, versionId: Option[String], updatedAt: Instant): Future[Unit] =
    val statement = versionId match
      case Some(_) => "UPDATE applet SET publishedVersionId = $versionId, updatedAt = $updatedAt WHERE appletId = $appletId RETURN NONE;"
      case None    => "UPDATE applet SET publishedVersionId = NONE, updatedAt = $updatedAt WHERE appletId = $appletId RETURN NONE;"
    val variables = Json.obj("appletId" -> appletId, "updatedAt" -> updatedAt) ++
      versionId.fold(Json.obj())(id => Json.obj("versionId" -> id))
    surrealClient.query(statement, variables).map(_ => ())

  override def deleteWithResources(appletId: String): Future[Unit] =
    val statements =
      """BEGIN TRANSACTION;
        |DELETE applet_storage WHERE appletId = $appletId;
        |DELETE applet_http_target WHERE appletId = $appletId;
        |DELETE applet_secret WHERE appletId = $appletId;
        |DELETE applet_variable WHERE appletId = $appletId;
        |DELETE applet_version WHERE appletId = $appletId;
        |DELETE applet WHERE appletId = $appletId;
        |COMMIT TRANSACTION;""".stripMargin
    surrealClient.query(statements, Json.obj("appletId" -> appletId)).map(_ => ())
