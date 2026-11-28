package com.cowork.applet.domain.environment.repository

import com.cowork.applet.domain.environment.model.AppletVariable
import com.cowork.applet.domain.environment.repository.impl.SurrealAppletVariableRepository
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealAppletVariableRepository])
trait AppletVariableRepository:
  def findAll(appletId: String): Future[Seq[AppletVariable]]
  def upsert(variable: AppletVariable): Future[Unit]
  def delete(appletId: String, name: String): Future[Boolean]
