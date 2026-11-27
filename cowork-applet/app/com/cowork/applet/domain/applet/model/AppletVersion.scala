package com.cowork.applet.domain.applet.model

import com.cowork.applet.domain.review.model.ReviewFinding
import java.time.Instant
import play.api.libs.json.{Json, OFormat}

// Summary reads leave files empty so that version lists do not load bundle contents.
// permissions are the app permissions that the version requests. A team grants them separately on its installation.
final case class AppletVersion(
    versionId: String,
    appletId: String,
    number: Int,
    status: AppletVersionStatus,
    findings: Seq[ReviewFinding],
    files: Seq[BundleFile],
    createdAt: Instant,
    permissions: Seq[String] = Nil
)

object AppletVersion:
  // Versions stored before permissions existed request none.
  given OFormat[AppletVersion] = Json.using[Json.WithDefaultValues].format[AppletVersion]
