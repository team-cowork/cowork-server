package com.cowork.applet.domain.review.model

import play.api.libs.json.{Json, OFormat}

// Bundle-level findings have no path or line.
final case class ReviewFinding(
    ruleId: String,
    severity: ReviewSeverity,
    message: String,
    path: Option[String] = None,
    line: Option[Int] = None
)

object ReviewFinding:
  given OFormat[ReviewFinding] = Json.format[ReviewFinding]
