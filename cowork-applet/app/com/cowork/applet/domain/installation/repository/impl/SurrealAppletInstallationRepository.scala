package com.cowork.applet.domain.installation.repository.impl

import com.cowork.applet.domain.installation.model.AppletInstallation
import com.cowork.applet.domain.installation.repository.AppletInstallationRepository
import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, Json}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SurrealAppletInstallationRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext)
    extends AppletInstallationRepository:

  override def findByTeam(teamId: Long): Future[Seq[AppletInstallation]] =
    surrealClient
      .query(
        "SELECT * OMIT id FROM applet_installation WHERE teamId = $teamId ORDER BY installedAt;",
        Json.obj("teamId" -> teamId)
      )
      .map(results => results.head.as[Seq[AppletInstallation]])

  override def create(installation: AppletInstallation): Future[Unit] =
    val statements =
      """LET $existing = (SELECT teamId FROM applet_installation WHERE teamId = $installation.teamId AND appletId = $installation.appletId);
        |IF array::len($existing) == 0 { CREATE applet_installation CONTENT $installation RETURN NONE; };""".stripMargin
    surrealClient.query(statements, Json.obj("installation" -> Json.toJsObject(installation))).map(_ => ())

  override def delete(teamId: Long, appletId: String): Future[Boolean] =
    surrealClient
      .query(
        "DELETE applet_installation WHERE teamId = $teamId AND appletId = $appletId RETURN BEFORE;",
        Json.obj("teamId" -> teamId, "appletId" -> appletId)
      )
      .map(results => results.head.as[JsArray].value.nonEmpty)
