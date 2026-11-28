package com.cowork.applet.domain.applet.model

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Json, OFormat, Reads, Writes}

enum FileEncoding(val value: String):
  // Text files: HTML, CSS, JavaScript, and JSON.
  case UTF8 extends FileEncoding("utf8")
  // Binary assets: images and fonts.
  case BASE64 extends FileEncoding("base64")

object FileEncoding:
  def parse(value: String): Option[FileEncoding] = values.find(_.value == value)

  given Format[FileEncoding] = Format(
    Reads {
      case JsString(value) => parse(value).fold(JsError("error.expected.encoding"))(JsSuccess(_))
      case _               => JsError("error.expected.jsstring")
    },
    Writes(encoding => JsString(encoding.value))
  )

final case class BundleFile(path: String, content: String, encoding: FileEncoding = FileEncoding.UTF8):
  def extension: String = path.lastIndexOf('.') match
    case -1    => ""
    case index => path.substring(index + 1).toLowerCase

object BundleFile:
  given OFormat[BundleFile] = Json.format[BundleFile]
