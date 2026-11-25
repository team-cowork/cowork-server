package com.cowork.applet.domain.team.repository

import com.cowork.applet.domain.team.model.TeamRolePolicy
import com.cowork.applet.domain.team.repository.impl.SurrealTeamProjectionRepository
import com.google.inject.ImplementedBy
import scala.concurrent.Future

@ImplementedBy(classOf[SurrealTeamProjectionRepository])
trait TeamProjectionRepository:
  def isActiveMember(teamId: Long, userId: Long): Future[Boolean]
  // Roles of the team plus the assignment and member rows of the user.
  def policiesFor(teamId: Long, userId: Long): Future[Seq[TeamRolePolicy]]
