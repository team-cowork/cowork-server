package com.cowork.applet.domain.applet.service

import com.cowork.applet.global.error.ExpectedException
import play.api.http.Status.BAD_REQUEST

object AppletProfiles:
  val MaxNameLength = 50
  val MaxDescriptionLength = 500

  def name(value: String): String =
    val trimmed = value.trim
    if trimmed.isEmpty || trimmed.length > MaxNameLength then
      throw ExpectedException(BAD_REQUEST, "앱 이름은 1자 이상 50자 이하로 입력하십시오.")
    trimmed

  def description(value: String): String =
    val trimmed = value.trim
    if trimmed.length > MaxDescriptionLength then
      throw ExpectedException(BAD_REQUEST, "앱 설명은 500자 이하로 입력하십시오.")
    trimmed
