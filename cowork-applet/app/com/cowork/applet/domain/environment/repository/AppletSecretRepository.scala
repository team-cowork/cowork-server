package com.cowork.applet.domain.environment.repository

import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.repository.impl.SurrealAppletSecretRepository
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealAppletSecretRepository])
trait AppletSecretRepository:
  def findAll(appletId: String): Future[Seq[AppletSecret]]
  def upsert(secret: AppletSecret): Future[Unit]
  def delete(appletId: String, name: String): Future[Boolean]
