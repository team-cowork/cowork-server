package com.cowork.applet.domain.network.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.repository.AppletHttpTargetRepository
import com.cowork.applet.domain.network.service.{HttpTargets, PutAppletHttpTargetService}
import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PutAppletHttpTargetServiceImpl @Inject() (
    appletReader: AppletReader,
    appletHttpTargetRepository: AppletHttpTargetRepository
)(using ExecutionContext)
    extends PutAppletHttpTargetService:

  override def execute(
      userId: String,
      appletId: String,
      host: String,
      headers: Map[String, String],
      query: Map[String, String]
  ): Future[AppletHttpTarget] =
    val validHost = HttpTargets.host(host)
    val validHeaders = HttpTargets.headers(headers)
    val validQuery = HttpTargets.query(query)
    for
      applet <- appletReader.owned(appletId, userId)
      existing <- appletHttpTargetRepository.findAll(applet.appletId)
      _ = HttpTargets.requireCapacity(existing.exists(_.host == validHost), existing.size)
      target = AppletHttpTarget(applet.appletId, validHost, validHeaders, validQuery, Instant.now())
      _ <- appletHttpTargetRepository.upsert(target)
    yield target
