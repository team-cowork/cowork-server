package com.cowork.applet.domain.team

import com.cowork.applet.domain.team.model.{TeamMemberEvent, TeamRolePolicy}
import java.time.Instant
import play.api.libs.json.Json

class TeamEventsTest extends munit.FunSuite:

  private val occurredAt = "2026-11-24T00:00:00.123456Z"

  test("TeamMemberEvent.parse / 올바른 UPSERT / 활성 멤버로 읽는다") {
    // Given
    val value = s"""{"eventType":"UPSERT","teamId":3,"userId":7,"role":"MEMBER","teamName":"팀","occurredAt":"$occurredAt"}"""

    // When
    val event = TeamMemberEvent.parse("3:7", value)

    // Then
    assertEquals(event.map(_.active), Right(true))
  }

  test("TeamMemberEvent.parse / 키 불일치, 알 수 없는 역할, 빈 팀 이름 / 거부한다") {
    // Given
    val valid = s"""{"eventType":"DELETE","teamId":3,"userId":7,"role":"MEMBER","teamName":"팀","occurredAt":"$occurredAt"}"""
    val cases = Seq(
      "3:8" -> valid,
      "3:7" -> valid.replace("MEMBER", "GUEST"),
      "3:7" -> valid.replace("\"팀\"", "\" \""),
      "3:7" -> "null"
    )

    // When / Then
    cases.foreach((key, value) => assert(TeamMemberEvent.parse(key, value).isLeft, value))
  }

  test("TeamRolePolicy.parse / 역할, 배정, 멤버 삭제 이벤트 / 키와 종류를 맞춰 읽는다") {
    // Given
    val role = s"""{"eventType":"ROLE_UPSERTED","teamId":3,"roleId":5,"isDefault":false,"permissions":{"manage_applets":true},"occurredAt":"$occurredAt"}"""
    val assignment = s"""{"eventType":"ASSIGNMENT_DELETED","teamId":3,"roleId":5,"accountId":7,"occurredAt":"$occurredAt"}"""
    val member = s"""{"eventType":"MEMBER_ASSIGNMENTS_DELETED","teamId":3,"accountId":7,"occurredAt":"$occurredAt"}"""

    // When
    val parsed = Seq("role:3:5" -> role, "assignment:3:7:5" -> assignment, "member:3:7" -> member).map(TeamRolePolicy.parse)

    // Then
    assertEquals(parsed.map(_.map(p => (p.kind, p.deleted))), Seq(Right(("ROLE", false)), Right(("ASSIGNMENT", true)), Right(("MEMBER", true))))
    assertEquals(parsed.head.map(_.sourceVersion), Right(TeamRolePolicy.micros(Instant.parse(occurredAt))))
  }

  test("TeamRolePolicy.parse / boolean이 아닌 권한 값과 키 불일치 / 거부한다") {
    // Given
    val role = s"""{"eventType":"ROLE_UPSERTED","teamId":3,"roleId":5,"isDefault":false,"permissions":{"manage_applets":"yes"},"occurredAt":"$occurredAt"}"""
    val valid = role.replace("\"yes\"", "true")

    // When / Then
    assert(TeamRolePolicy.parse("role:3:5", role).isLeft)
    assert(TeamRolePolicy.parse("role:3:6", valid).isLeft)
  }

  private def rolePolicy(roleId: Long, permission: String, isDefault: Boolean = false, deleted: Boolean = false) =
    TeamRolePolicy(s"role:3:$roleId", 3, "ROLE", Some(roleId), None, isDefault, Json.obj(permission -> true), deleted, 1)

  private def assignment(roleId: Long, version: Long, deleted: Boolean = false) =
    TeamRolePolicy(s"assignment:3:7:$roleId", 3, "ASSIGNMENT", Some(roleId), Some(7), false, Json.obj(), deleted, version)

  test("allows / 배정된 역할 또는 기본 역할의 권한 / 허용한다") {
    // When / Then
    assert(TeamRolePolicy.allows(Seq(rolePolicy(5, "manage_applets"), assignment(5, 10)), 7, "manage_applets"))
    assert(TeamRolePolicy.allows(Seq(rolePolicy(1, "manage_applets", isDefault = true)), 7, "manage_applets"))
  }

  test("allows / 배정 없음, 삭제된 배정, 삭제된 역할, 다른 권한 / 거부한다") {
    // Given
    val cases = Seq(
      Seq(rolePolicy(5, "manage_applets")),
      Seq(rolePolicy(5, "manage_applets"), assignment(5, 10, deleted = true)),
      Seq(rolePolicy(5, "manage_applets", deleted = true), assignment(5, 10)),
      Seq(rolePolicy(5, "manage_team"), assignment(5, 10))
    )

    // When / Then
    cases.foreach(policies => assert(!TeamRolePolicy.allows(policies, 7, "manage_applets"), policies.toString))
  }

  test("allows / 배정 이후에 멤버의 배정이 모두 삭제됨 / 거부하고 그 뒤 새 배정은 허용한다") {
    // Given
    val removed = TeamRolePolicy("member:3:7", 3, "MEMBER", None, Some(7), false, Json.obj(), true, 20)

    // When / Then
    assert(!TeamRolePolicy.allows(Seq(rolePolicy(5, "manage_applets"), assignment(5, 10), removed), 7, "manage_applets"))
    assert(TeamRolePolicy.allows(Seq(rolePolicy(5, "manage_applets"), assignment(5, 30), removed), 7, "manage_applets"))
  }
