package com.cowork.applet.domain.applet.model

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Reads, Writes}
import scala.util.Try

enum AppletVersionStatus:
  // Passed the review. The owner can publish it again while its files are kept.
  case APPROVED
  // Failed the review. Only the findings are kept.
  case REJECTED
  // Passed the review, but the retention policy removed its files. It cannot be published again.
  case ARCHIVED

object AppletVersionStatus:
  given Format[AppletVersionStatus] = Format(
    Reads {
      case JsString(value) => Try(valueOf(value)).fold(_ => JsError("error.expected.status"), JsSuccess(_))
      case _               => JsError("error.expected.jsstring")
    },
    Writes(status => JsString(status.toString))
  )
