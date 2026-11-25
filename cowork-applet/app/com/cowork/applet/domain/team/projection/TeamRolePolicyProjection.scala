package com.cowork.applet.domain.team.projection

import com.cowork.applet.domain.team.model.TeamRolePolicy
import com.cowork.applet.global.projection.{ProjectionMutation, ProjectionRecordHandler}
import org.apache.kafka.clients.consumer.ConsumerRecord
import play.api.libs.json.Json

object TeamRolePolicyProjection extends ProjectionRecordHandler:
  val topic = "preference.team-role.state.v2"
  val group = "cowork-applet.team-role.v2"
  val snapshotSource = "cowork-preference"

  // A newer version replaces the row. The same version can only add a deletion.
  private val Statements =
    """LET $current = (SELECT * FROM applet_team_role_policy WHERE policyKey = $policy.policyKey);
      |IF array::len($current) == 0 {
      |  CREATE applet_team_role_policy CONTENT $policy RETURN NONE;
      |} ELSE IF $policy.sourceVersion > $current[0].sourceVersion {
      |  UPDATE applet_team_role_policy SET isDefault = $policy.isDefault, permissions = $policy.permissions,
      |    deleted = $policy.deleted, sourceVersion = $policy.sourceVersion WHERE policyKey = $policy.policyKey RETURN NONE;
      |} ELSE IF $policy.sourceVersion == $current[0].sourceVersion AND $policy.deleted {
      |  UPDATE applet_team_role_policy SET deleted = true WHERE policyKey = $policy.policyKey RETURN NONE;
      |};""".stripMargin

  def mutation(record: ConsumerRecord[String, String]): Either[String, ProjectionMutation] =
    Option(record.value()).toRight("Role policy state top-level null is not allowed").flatMap { value =>
      TeamRolePolicy.parse(record.key(), value).map(policy => ProjectionMutation(Statements, Json.obj("policy" -> Json.toJsObject(policy))))
    }
