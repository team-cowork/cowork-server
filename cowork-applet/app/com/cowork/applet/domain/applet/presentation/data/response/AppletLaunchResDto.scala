package com.cowork.applet.domain.applet.presentation.data.response

import com.cowork.applet.domain.applet.model.AppletLaunch
import com.cowork.applet.domain.review.service.BundleReviewer
import play.api.libs.json.{Json, OWrites}

final case class BundleFileResDto(path: String, content: String, encoding: String)

object BundleFileResDto:
  given OWrites[BundleFileResDto] = Json.writes[BundleFileResDto]

final case class EnvironmentEntryResDto(name: String, value: String)

object EnvironmentEntryResDto:
  given OWrites[EnvironmentEntryResDto] = Json.writes[EnvironmentEntryResDto]

final case class AppletLaunchResDto(
    appletId: String,
    versionId: String,
    entry: String,
    files: Seq[BundleFileResDto],
    environment: Seq[EnvironmentEntryResDto]
)

object AppletLaunchResDto:
  given OWrites[AppletLaunchResDto] = Json.writes[AppletLaunchResDto]

  def from(launch: AppletLaunch): AppletLaunchResDto = AppletLaunchResDto(
    launch.applet.appletId,
    launch.version.versionId,
    BundleReviewer.EntryPath,
    launch.version.files.map(file => BundleFileResDto(file.path, file.content, file.encoding.value)),
    launch.environment.toSeq.sortBy(_._1).map((name, value) => EnvironmentEntryResDto(name, value))
  )
