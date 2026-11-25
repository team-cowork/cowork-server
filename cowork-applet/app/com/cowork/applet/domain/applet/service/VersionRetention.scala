package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.{AppletVersion, AppletVersionStatus}

// Limits stored bundles: the published version plus the latest approved versions keep their files for rollback.
// Older approved versions keep only their record, and old rejected versions are deleted.
object VersionRetention:
  val KeptApprovedVersions = 5
  val KeptRejectedVersions = 10

  final case class Plan(archive: Seq[String], delete: Seq[String])

  def plan(versions: Seq[AppletVersion], publishedVersionId: Option[String]): Plan =
    val newestFirst = versions.sortBy(-_.number)
    val archive = newestFirst
      .filter(_.status == AppletVersionStatus.APPROVED)
      .filterNot(version => publishedVersionId.contains(version.versionId))
      .drop(KeptApprovedVersions)
      .map(_.versionId)
    val delete = newestFirst.filter(_.status == AppletVersionStatus.REJECTED).drop(KeptRejectedVersions).map(_.versionId)
    Plan(archive, delete)
