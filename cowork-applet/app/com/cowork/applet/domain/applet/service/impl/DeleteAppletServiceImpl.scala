package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.applet.service.{AppletReader, DeleteAppletService}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeleteAppletServiceImpl @Inject() (appletReader: AppletReader, appletRepository: AppletRepository)(using
    ExecutionContext
) extends DeleteAppletService:

  override def execute(userId: String, appletId: String): Future[Unit] =
    appletReader.owned(appletId, userId).flatMap(applet => appletRepository.deleteWithResources(applet.appletId))
