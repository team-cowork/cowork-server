package com.cowork.applet.domain.storage.service

import com.cowork.applet.global.error.ExpectedException
import java.nio.charset.StandardCharsets.UTF_8
import play.api.http.Status.BAD_REQUEST
import play.api.libs.json.{JsValue, Json}

// Free tier limits for the storage that the platform provides to every applet.
object StorageLimits:
  val MaxEntriesPerApplet = 1000
  // USER entries of one user. Without it one user could fill the applet limit for everyone.
  val MaxEntriesPerUser = 100
  val MaxValueBytes = 16 * 1024
  val DefaultListLimit = 50
  val MaxListLimit = 100

  private val KeyPattern = """[A-Za-z0-9._:/-]{1,128}""".r

  def key(value: String): String =
    if !KeyPattern.matches(value) then
      throw ExpectedException(BAD_REQUEST, "저장소 키는 영문, 숫자, 일부 기호를 사용하여 128자 이하로 입력하십시오.")
    value

  def prefix(value: String): String =
    if value.nonEmpty then key(value) else value

  def value(content: JsValue): JsValue =
    if Json.stringify(content).getBytes(UTF_8).length > MaxValueBytes then
      throw ExpectedException(BAD_REQUEST, "저장소 값은 16KB를 넘을 수 없습니다.")
    content

  def listLimit(requested: Option[Int]): Int = requested match
    case None                                              => DefaultListLimit
    case Some(limit) if limit >= 1 && limit <= MaxListLimit => limit
    case Some(_) => throw ExpectedException(BAD_REQUEST, "조회 개수는 1 이상 100 이하로 입력하십시오.")

  def requireCapacity(exists: Boolean, count: Int): Unit =
    if !exists && count >= MaxEntriesPerApplet then
      throw ExpectedException(BAD_REQUEST, "앱 저장소에는 최대 1000개까지 저장할 수 있습니다.")

  def requireUserCapacity(exists: Boolean, count: Int): Unit =
    if !exists && count >= MaxEntriesPerUser then
      throw ExpectedException(BAD_REQUEST, "사용자 한 명은 앱 저장소에 최대 100개까지 저장할 수 있습니다.")
