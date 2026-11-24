package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.applet.service.{AppletProfiles, AppletReader, UpdateAppletService}
import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpdateAppletServiceImpl @Inject() (appletReader: AppletReader, appletRepository: AppletRepository)(using
    ExecutionContext
) extends UpdateAppletService:

  override def execute(userId: String, appletId: String, name: Option[String], description: Option[String]): Future[Applet] =
    appletReader.owned(appletId, userId).flatMap { applet =>
      val updated = applet.copy(
        name = name.map(AppletProfiles.name).getOrElse(applet.name),
        description = description.map(AppletProfiles.description).getOrElse(applet.description),
        updatedAt = Instant.now()
      )
      appletRepository.updateProfile(updated).map(_ => updated)
    }
