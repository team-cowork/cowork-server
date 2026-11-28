package com.cowork.applet.domain.team.repository.impl

import com.cowork.applet.domain.team.model.TeamRolePolicy
import com.cowork.applet.domain.team.repository.TeamProjectionRepository
import com.cowork.applet.global.surreal.SurrealClient
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, Json}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SurrealTeamProjectionRepository @Inject() (surrealClient: SurrealClient)(using ExecutionContext)
    extends TeamProjectionRepository:

  override def isActiveMember(teamId: Long, userId: Long): Future[Boolean] =
    surrealClient
      .query(
        "SELECT teamId FROM applet_team_member WHERE teamId = $teamId AND userId = $userId AND active = true LIMIT 1;",
        Json.obj("teamId" -> teamId, "userId" -> userId)
      )
      .map(results => results.head.as[JsArray].value.nonEmpty)

  override def policiesFor(teamId: Long, userId: Long): Future[Seq[TeamRolePolicy]] =
    surrealClient
      .query(
        "SELECT * OMIT id FROM applet_team_role_policy WHERE teamId = $teamId AND (kind = 'ROLE' OR accountId = $userId);",
        Json.obj("teamId" -> teamId, "userId" -> userId)
      )
      .map(results => results.head.as[Seq[TeamRolePolicy]])
