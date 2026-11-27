package com.cowork.applet.domain.applet.presentation.data.request

import play.api.libs.json.{Json, Reads}

// encoding is utf8 for text files and base64 for images and fonts. It defaults to utf8.
final case class BundleFileReqDto(path: String, content: String, encoding: Option[String])

object BundleFileReqDto:
  given Reads[BundleFileReqDto] = Json.reads[BundleFileReqDto]

// permissions are the app permissions that the version requests. They default to none.
final case class SubmitAppletVersionReqDto(files: Seq[BundleFileReqDto], permissions: Option[Seq[String]])

object SubmitAppletVersionReqDto:
  given Reads[SubmitAppletVersionReqDto] = Json.reads[SubmitAppletVersionReqDto]
