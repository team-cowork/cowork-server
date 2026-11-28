package com.cowork.applet.domain.environment.repository.impl

import com.cowork.applet.domain.environment.model.AppletVariable
import com.cowork.applet.domain.environment.repository.AppletVariableRepository
import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, Json}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SurrealAppletVariableRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext)
    extends AppletVariableRepository:

  private val Table = "applet_variable"

  override def findAll(appletId: String): Future[Seq[AppletVariable]] =
    surrealClient
      .query(SurrealEnvironmentStatements.findAll(Table), Json.obj("appletId" -> appletId))
      .map(results => results.head.as[Seq[AppletVariable]])

  override def upsert(variable: AppletVariable): Future[Unit] =
    surrealClient
      .query(SurrealEnvironmentStatements.upsert(Table, "value"), Json.obj("entry" -> Json.toJsObject(variable)))
      .map(_ => ())

  override def delete(appletId: String, name: String): Future[Boolean] =
    surrealClient
      .query(SurrealEnvironmentStatements.delete(Table), Json.obj("appletId" -> appletId, "name" -> name))
      .map(results => results.head.as[JsArray].value.nonEmpty)
