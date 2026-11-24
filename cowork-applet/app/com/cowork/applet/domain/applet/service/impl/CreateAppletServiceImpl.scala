package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.applet.service.{AppletProfiles, CreateAppletService}
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CreateAppletServiceImpl @Inject() (appletRepository: AppletRepository)(using ExecutionContext)
    extends CreateAppletService:

  override def execute(userId: String, name: String, description: Option[String]): Future[Applet] =
    val now = Instant.now()
    val applet = Applet(
      appletId = UUID.randomUUID().toString,
      ownerId = userId,
      name = AppletProfiles.name(name),
      description = description.map(AppletProfiles.description).getOrElse(""),
      publishedVersionId = None,
      createdAt = now,
      updatedAt = now
    )
    appletRepository.create(applet).map(_ => applet)
