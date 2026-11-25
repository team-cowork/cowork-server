package com.cowork.applet.domain.storage.model

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Reads, Writes}
import scala.util.Try

enum StorageScope:
  // Entries visible only to the calling user.
  case USER
  // Entries shared by every user of the applet. Any user who can run the applet can change them.
  case APPLET

object StorageScope:
  given Format[StorageScope] = Format(
    Reads {
      case JsString(value) => Try(valueOf(value)).fold(_ => JsError("error.expected.scope"), JsSuccess(_))
      case _               => JsError("error.expected.jsstring")
    },
    Writes(scope => JsString(scope.toString))
  )
