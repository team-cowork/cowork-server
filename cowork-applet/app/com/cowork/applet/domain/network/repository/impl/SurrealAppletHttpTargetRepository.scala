package com.cowork.applet.domain.network.repository.impl

import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.repository.AppletHttpTargetRepository
import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, Json}
import scala.concurrent.{ExecutionContext, Future}

// `query` is escaped in case the SurrealQL parser treats it as a keyword.
@Singleton
class SurrealAppletHttpTargetRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext)
    extends AppletHttpTargetRepository:

  override def findAll(appletId: String): Future[Seq[AppletHttpTarget]] =
    surrealClient
      .query("SELECT * OMIT id FROM applet_http_target WHERE appletId = $appletId ORDER BY host;", Json.obj("appletId" -> appletId))
      .map(results => results.head.as[Seq[AppletHttpTarget]])

  override def find(appletId: String, host: String): Future[Option[AppletHttpTarget]] =
    surrealClient
      .query(
        "SELECT * OMIT id FROM applet_http_target WHERE appletId = $appletId AND host = $host LIMIT 1;",
        Json.obj("appletId" -> appletId, "host" -> host)
      )
      .map(results => results.head.as[Seq[AppletHttpTarget]].headOption)

  override def upsert(target: AppletHttpTarget): Future[Unit] =
    val statements =
      """LET $updated = (UPDATE applet_http_target SET headers = $entry.headers, `query` = $entry.`query`, updatedAt = $entry.updatedAt
        |  WHERE appletId = $entry.appletId AND host = $entry.host);
        |IF array::len($updated) == 0 { CREATE applet_http_target CONTENT $entry RETURN NONE; };""".stripMargin
    surrealClient.query(statements, Json.obj("entry" -> Json.toJsObject(target))).map(_ => ())

  override def delete(appletId: String, host: String): Future[Boolean] =
    surrealClient
      .query(
        "DELETE applet_http_target WHERE appletId = $appletId AND host = $host RETURN BEFORE;",
        Json.obj("appletId" -> appletId, "host" -> host)
      )
      .map(results => results.head.as[JsArray].value.nonEmpty)
