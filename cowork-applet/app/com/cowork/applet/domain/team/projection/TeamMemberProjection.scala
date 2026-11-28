package com.cowork.applet.domain.team.projection

import com.cowork.applet.domain.team.model.{TeamMemberEvent, TeamRolePolicy}
import com.cowork.applet.global.projection.{ProjectionMutation, ProjectionRecordHandler}
import org.apache.kafka.clients.consumer.ConsumerRecord
import play.api.libs.json.Json

object TeamMemberProjection extends ProjectionRecordHandler:
  val topic = "team.member.event"
  val group = "cowork-applet.team-member"
  val snapshotSource = "cowork-team"

  // A newer event replaces the row. An event with the same time keeps the member active only if both say active.
  private val Statements =
    """LET $current = (SELECT * FROM applet_team_member WHERE teamId = $member.teamId AND userId = $member.userId);
      |IF array::len($current) == 0 {
      |  CREATE applet_team_member CONTENT $member RETURN NONE;
      |} ELSE IF $member.occurredAt > $current[0].occurredAt {
      |  UPDATE applet_team_member SET role = $member.role, active = $member.active, occurredAt = $member.occurredAt
      |    WHERE teamId = $member.teamId AND userId = $member.userId RETURN NONE;
      |} ELSE IF $member.occurredAt == $current[0].occurredAt {
      |  UPDATE applet_team_member SET role = $member.role, active = active AND $member.active
      |    WHERE teamId = $member.teamId AND userId = $member.userId RETURN NONE;
      |};""".stripMargin

  def mutation(record: ConsumerRecord[String, String]): Either[String, ProjectionMutation] =
    Option(record.value()).toRight("team.member.event top-level null is not allowed").flatMap { value =>
      TeamMemberEvent.parse(record.key(), value).map { event =>
        ProjectionMutation(
          Statements,
          Json.obj(
            "member" -> Json.obj(
              "teamId" -> event.teamId,
              "userId" -> event.userId,
              "role" -> event.role,
              "active" -> event.active,
              "occurredAt" -> TeamRolePolicy.micros(event.occurredAt)
            )
          )
        )
      }
    }
