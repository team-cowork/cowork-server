package com.cowork.applet.domain.applet.presentation.data.request

import play.api.libs.json.{Json, Reads}

// encoding is utf8 for text files and base64 for images and fonts. It defaults to utf8.
final case class BundleFileReqDto(path: String, content: String, encoding: Option[String])

object BundleFileReqDto:
  given Reads[BundleFileReqDto] = Json.reads[BundleFileReqDto]

final case class SubmitAppletVersionReqDto(files: Seq[BundleFileReqDto])

object SubmitAppletVersionReqDto:
  given Reads[SubmitAppletVersionReqDto] = Json.reads[SubmitAppletVersionReqDto]
