package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.domain.applet.service.{AppletReader, UnpublishAppletService}
import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

// Hides the applet from other users at once, for example after a problem is found in the published version.
@Singleton
class UnpublishAppletServiceImpl @Inject() (appletReader: AppletReader, appletRepository: AppletRepository)(using
    ExecutionContext
) extends UnpublishAppletService:

  override def execute(userId: String, appletId: String): Future[Applet] =
    for
      applet <- appletReader.owned(appletId, userId)
      now = Instant.now()
      _ <- appletRepository.updatePublishedVersion(applet.appletId, None, now)
    yield applet.copy(publishedVersionId = None, updatedAt = now)
