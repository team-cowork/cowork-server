package com.cowork.applet.domain.team.model

import java.time.Instant
import play.api.libs.json.{JsObject, JsValue, Json, OFormat}
import scala.util.Try

// One row of preference.team-role.state.v2. kind is ROLE, ASSIGNMENT, or MEMBER.
// MEMBER rows record that every assignment of the member up to sourceVersion was removed.
final case class TeamRolePolicy(
    policyKey: String,
    teamId: Long,
    kind: String,
    roleId: Option[Long],
    accountId: Option[Long],
    isDefault: Boolean,
    permissions: JsObject,
    deleted: Boolean,
    sourceVersion: Long
):
  def grants(permission: String): Boolean = (permissions \ permission).asOpt[Boolean].contains(true)

object TeamRolePolicy:
  given OFormat[TeamRolePolicy] = Json.format[TeamRolePolicy]

  def micros(instant: Instant): Long = instant.getEpochSecond * 1_000_000L + instant.getNano / 1000

  def parse(key: String, value: String): Either[String, TeamRolePolicy] =
    Try(Json.parse(value).as[JsObject]).toOption.toRight("Invalid role policy state").flatMap { json =>
      def id(field: String): Option[Long] = (json \ field).asOpt[Long].filter(_ > 0)
      val parsed = for
        teamId <- id("teamId")
        eventType <- (json \ "eventType").asOpt[String]
        version <- (json \ "occurredAt").asOpt[String].flatMap(text => Try(Instant.parse(text)).toOption).map(micros)
        state <- eventType match
          case "MEMBER_ASSIGNMENTS_DELETED" =>
            id("accountId").map(account =>
              TeamRolePolicy(s"member:$teamId:$account", teamId, "MEMBER", None, Some(account), false, Json.obj(), true, version)
            )
          case "ASSIGNMENT_UPSERTED" | "ASSIGNMENT_DELETED" =>
            for
              role <- id("roleId")
              account <- id("accountId")
            yield TeamRolePolicy(
              s"assignment:$teamId:$account:$role", teamId, "ASSIGNMENT", Some(role), Some(account), false, Json.obj(),
              eventType == "ASSIGNMENT_DELETED", version
            )
          case "ROLE_DELETED" =>
            id("roleId").map(role => TeamRolePolicy(s"role:$teamId:$role", teamId, "ROLE", Some(role), None, false, Json.obj(), true, version))
          case "ROLE_UPSERTED" =>
            for
              role <- id("roleId")
              permissions <- (json \ "permissions").asOpt[JsObject].filter(_.values.forall(_.asOpt[Boolean].isDefined))
              isDefault <- (json \ "isDefault").asOpt[Boolean]
            yield TeamRolePolicy(s"role:$teamId:$role", teamId, "ROLE", Some(role), None, isDefault, permissions, false, version)
          case _ => None
      yield state
      parsed.toRight("Invalid role policy state").filterOrElse(_.policyKey == key, "Role policy key does not match payload identity")
    }

  // Ported from the Roadmap policy query. Default roles apply to every member. Other roles need a live assignment
  // that no later MEMBER_ASSIGNMENTS_DELETED row has removed.
  def allows(policies: Seq[TeamRolePolicy], userId: Long, permission: String): Boolean =
    val removedUpTo = policies.filter(p => p.kind == "MEMBER" && p.accountId.contains(userId)).map(_.sourceVersion).maxOption
    val assignedRoles = policies.collect {
      case p if p.kind == "ASSIGNMENT" && p.accountId.contains(userId) && !p.deleted && removedUpTo.forall(_ < p.sourceVersion) =>
        p.roleId
    }.flatten.toSet
    policies.exists(p =>
      p.kind == "ROLE" && !p.deleted && p.grants(permission) && (p.isDefault || p.roleId.exists(assignedRoles.contains))
    )
