package com.cowork.applet.domain.environment.repository.impl

import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.repository.AppletSecretRepository
import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, Json}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SurrealAppletSecretRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext)
    extends AppletSecretRepository:

  private val Table = "applet_secret"

  override def findAll(appletId: String): Future[Seq[AppletSecret]] =
    surrealClient
      .query(SurrealEnvironmentStatements.findAll(Table), Json.obj("appletId" -> appletId))
      .map(results => results.head.as[Seq[AppletSecret]])

  override def upsert(secret: AppletSecret): Future[Unit] =
    surrealClient
      .query(SurrealEnvironmentStatements.upsert(Table, "ciphertext"), Json.obj("entry" -> Json.toJsObject(secret)))
      .map(_ => ())

  override def delete(appletId: String, name: String): Future[Boolean] =
    surrealClient
      .query(SurrealEnvironmentStatements.delete(Table), Json.obj("appletId" -> appletId, "name" -> name))
      .map(results => results.head.as[JsArray].value.nonEmpty)
