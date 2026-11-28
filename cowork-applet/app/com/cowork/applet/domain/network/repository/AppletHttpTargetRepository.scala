package com.cowork.applet.domain.network.repository

import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.repository.impl.SurrealAppletHttpTargetRepository
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealAppletHttpTargetRepository])
trait AppletHttpTargetRepository:
  def findAll(appletId: String): Future[Seq[AppletHttpTarget]]
  def find(appletId: String, host: String): Future[Option[AppletHttpTarget]]
  def upsert(target: AppletHttpTarget): Future[Unit]
  def delete(appletId: String, host: String): Future[Boolean]
