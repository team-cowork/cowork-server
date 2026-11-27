package com.cowork.applet.domain.applet.presentation.data.response

import com.cowork.applet.domain.applet.model.AppletVersion
import com.cowork.applet.domain.review.model.ReviewFinding
import java.time.Instant
import play.api.libs.json.{Json, OWrites}

final case class ReviewFindingResDto(
    ruleId: String,
    severity: String,
    message: String,
    path: Option[String],
    line: Option[Int]
)

object ReviewFindingResDto:
  given OWrites[ReviewFindingResDto] = Json.writes[ReviewFindingResDto]

  def from(finding: ReviewFinding): ReviewFindingResDto =
    ReviewFindingResDto(finding.ruleId, finding.severity.toString, finding.message, finding.path, finding.line)

final case class AppletVersionResDto(
    versionId: String,
    number: Int,
    status: String,
    findings: Seq[ReviewFindingResDto],
    permissions: Seq[String],
    createdAt: Instant
)

object AppletVersionResDto:
  given OWrites[AppletVersionResDto] = Json.writes[AppletVersionResDto]

  def from(version: AppletVersion): AppletVersionResDto = AppletVersionResDto(
    version.versionId,
    version.number,
    version.status.toString,
    version.findings.map(ReviewFindingResDto.from),
    version.permissions,
    version.createdAt
  )
