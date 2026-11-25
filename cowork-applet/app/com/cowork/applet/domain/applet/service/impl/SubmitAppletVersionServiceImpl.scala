package com.cowork.applet.domain.applet.service.impl

import com.cowork.applet.domain.applet.model.{AppletVersion, AppletVersionStatus, BundleFile}
import com.cowork.applet.domain.applet.repository.AppletVersionRepository
import com.cowork.applet.domain.applet.service.{AppletReader, SubmitAppletVersionService, VersionRetention}
import com.cowork.applet.domain.review.service.BundleReviewer
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import play.api.Logger
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

// The review runs before storage. A passing version becomes the published version at once;
// a rejected version keeps only its findings so that rejected code is not stored.
// Each upload then applies VersionRetention to bound the stored bundles per applet.
@Singleton
class SubmitAppletVersionServiceImpl @Inject() (
    appletReader: AppletReader,
    appletVersionRepository: AppletVersionRepository
)(using ExecutionContext)
    extends SubmitAppletVersionService:

  private val logger = Logger(getClass)

  override def execute(userId: String, appletId: String, files: Seq[BundleFile]): Future[AppletVersion] =
    appletReader.owned(appletId, userId).flatMap { applet =>
      val findings = BundleReviewer.review(files)
      val approved = BundleReviewer.passes(findings)
      appletVersionRepository.nextNumber(applet.appletId).flatMap { number =>
        val now = Instant.now()
        val version = AppletVersion(
          versionId = UUID.randomUUID().toString,
          appletId = applet.appletId,
          number = number,
          status = if approved then AppletVersionStatus.APPROVED else AppletVersionStatus.REJECTED,
          findings = findings,
          files = if approved then files else Nil,
          createdAt = now
        )
        val stored =
          if approved then appletVersionRepository.createAndPublish(version, now)
          else appletVersionRepository.create(version)
        stored.flatMap { _ =>
          logger.info(s"Reviewed applet version ${version.versionId} with status ${version.status}.")
          val publishedVersionId = if approved then Some(version.versionId) else applet.publishedVersionId
          applyRetention(applet.appletId, publishedVersionId).map(_ => version)
        }
      }
    }

  // The new version is already stored, so a retention failure only delays cleanup until the next upload.
  private def applyRetention(appletId: String, publishedVersionId: Option[String]): Future[Unit] =
    appletVersionRepository
      .findSummaries(appletId)
      .flatMap { versions =>
        val plan = VersionRetention.plan(versions, publishedVersionId)
        appletVersionRepository.archive(appletId, plan.archive).flatMap(_ => appletVersionRepository.delete(appletId, plan.delete))
      }
      .recover { case NonFatal(error) => logger.warn(s"Failed to apply version retention for applet $appletId.", error) }
