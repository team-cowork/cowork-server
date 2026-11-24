package com.cowork.applet.domain.review.model

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Reads, Writes}
import scala.util.Try

enum ReviewSeverity:
  // Rejects the version.
  case BLOCK
  // Records a problem that the runtime sandbox already contains; the version still passes.
  case WARN

object ReviewSeverity:
  given Format[ReviewSeverity] = Format(
    Reads {
      case JsString(value) => Try(valueOf(value)).fold(_ => JsError("error.expected.severity"), JsSuccess(_))
      case _               => JsError("error.expected.jsstring")
    },
    Writes(severity => JsString(severity.toString))
  )
