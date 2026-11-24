package com.cowork.applet.domain.team.model

import java.time.Instant
import play.api.libs.json.{JsObject, Json}
import scala.util.Try

// team.member.event from cowork-team. The record key is "{teamId}:{userId}".
final case class TeamMemberEvent(eventType: String, teamId: Long, userId: Long, role: String, occurredAt: Instant):
  def active: Boolean = eventType == "UPSERT"

object TeamMemberEvent:
  private val EventTypes = Set("UPSERT", "DELETE")
  private val Roles = Set("OWNER", "ADMIN", "MEMBER")

  def parse(key: String, value: String): Either[String, TeamMemberEvent] =
    Try(Json.parse(value).as[JsObject]).toOption.toRight("team.member.event JSON is malformed").flatMap { json =>
      val event = for
        eventType <- (json \ "eventType").asOpt[String].filter(EventTypes.contains)
        teamId <- (json \ "teamId").asOpt[Long].filter(_ > 0)
        userId <- (json \ "userId").asOpt[Long].filter(_ > 0)
        role <- (json \ "role").asOpt[String].filter(Roles.contains)
        _ <- (json \ "teamName").asOpt[String].filter(_.trim.nonEmpty)
        occurredAt <- (json \ "occurredAt").asOpt[String].flatMap(text => Try(Instant.parse(text)).toOption)
      yield TeamMemberEvent(eventType, teamId, userId, role, occurredAt)
      event
        .toRight("Invalid team.member.event payload")
        .filterOrElse(e => key == s"${e.teamId}:${e.userId}", "team.member.event key does not match payload identity")
    }
