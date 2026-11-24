package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.applet.service.QueryMyAppletsService
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class QueryMyAppletsServiceImpl @Inject() (appletRepository: AppletRepository) extends QueryMyAppletsService:
  override def execute(userId: String): Future[Seq[Applet]] = appletRepository.findByOwner(userId)
