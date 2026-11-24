package com.cowork.applet.domain.applet.model

import com.cowork.applet.domain.review.model.ReviewFinding
import java.time.Instant
import play.api.libs.json.{Json, OFormat}

// Summary reads leave files empty so that version lists do not load bundle contents.
final case class AppletVersion(
    versionId: String,
    appletId: String,
    number: Int,
    status: AppletVersionStatus,
    findings: Seq[ReviewFinding],
    files: Seq[BundleFile],
    createdAt: Instant
)

object AppletVersion:
  given OFormat[AppletVersion] = Json.format[AppletVersion]
