package com.cowork.applet.domain.applet.repository.impl

import com.cowork.applet.domain.applet.model.AppletVersion
import com.cowork.applet.domain.applet.repository.AppletVersionRepository
import com.cowork.applet.global.surreal.SurrealClient
import java.time.Instant
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SurrealAppletVersionRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext)
    extends AppletVersionRepository:

  override def nextNumber(appletId: String): Future[Int] =
    surrealClient
      .query(
        "SELECT math::max(number) AS latest FROM applet_version WHERE appletId = $appletId GROUP ALL;",
        Json.obj("appletId" -> appletId)
      )
      .map(results => results.head.as[Seq[JsObject]].headOption.flatMap(row => (row \ "latest").asOpt[Int]).getOrElse(0) + 1)

  override def create(version: AppletVersion): Future[Unit] =
    surrealClient
      .query("CREATE applet_version CONTENT $version RETURN NONE;", Json.obj("version" -> Json.toJsObject(version)))
      .map(_ => ())

  override def createAndPublish(version: AppletVersion, publishedAt: Instant): Future[Unit] =
    val statements =
      """BEGIN TRANSACTION;
        |CREATE applet_version CONTENT $version RETURN NONE;
        |UPDATE applet SET publishedVersionId = $versionId, updatedAt = $publishedAt WHERE appletId = $appletId RETURN NONE;
        |COMMIT TRANSACTION;""".stripMargin
    val variables = Json.obj(
      "version" -> Json.toJsObject(version),
      "versionId" -> version.versionId,
      "appletId" -> version.appletId,
      "publishedAt" -> publishedAt
    )
    surrealClient.query(statements, variables).map(_ => ())

  override def findSummaries(appletId: String): Future[Seq[AppletVersion]] =
    surrealClient
      .query(
        "SELECT * OMIT id, files FROM applet_version WHERE appletId = $appletId ORDER BY number DESC;",
        Json.obj("appletId" -> appletId)
      )
      .map(results => SurrealAppletVersionRepository.summaries(results.head))

  override def findSummary(appletId: String, versionId: String): Future[Option[AppletVersion]] =
    surrealClient
      .query(
        "SELECT * OMIT id, files FROM applet_version WHERE appletId = $appletId AND versionId = $versionId LIMIT 1;",
        Json.obj("appletId" -> appletId, "versionId" -> versionId)
      )
      .map(results => SurrealAppletVersionRepository.summaries(results.head).headOption)

  override def findWithFiles(appletId: String, versionId: String): Future[Option[AppletVersion]] =
    surrealClient
      .query(
        "SELECT * OMIT id FROM applet_version WHERE appletId = $appletId AND versionId = $versionId LIMIT 1;",
        Json.obj("appletId" -> appletId, "versionId" -> versionId)
      )
      .map(results => results.head.as[Seq[AppletVersion]].headOption)

  override def archive(appletId: String, versionIds: Seq[String]): Future[Unit] =
    if versionIds.isEmpty then Future.unit
    else
      surrealClient
        .query(
          "UPDATE applet_version SET status = 'ARCHIVED', files = [] WHERE appletId = $appletId AND versionId IN $versionIds RETURN NONE;",
          Json.obj("appletId" -> appletId, "versionIds" -> versionIds)
        )
        .map(_ => ())

  override def delete(appletId: String, versionIds: Seq[String]): Future[Unit] =
    if versionIds.isEmpty then Future.unit
    else
      surrealClient
        .query(
          "DELETE applet_version WHERE appletId = $appletId AND versionId IN $versionIds;",
          Json.obj("appletId" -> appletId, "versionIds" -> versionIds)
        )
        .map(_ => ())

object SurrealAppletVersionRepository:
  private def summaries(result: JsValue): Seq[AppletVersion] =
    result.as[Seq[JsObject]].map(row => (row + ("files" -> JsArray())).as[AppletVersion])
