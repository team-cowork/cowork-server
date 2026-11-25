package com.cowork.applet.domain.team.service

import com.cowork.applet.domain.team.model.TeamRolePolicy
import com.cowork.applet.domain.team.projection.TeamProjectionReadiness
import com.cowork.applet.domain.team.repository.TeamProjectionRepository
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.{FORBIDDEN, SERVICE_UNAVAILABLE}
import scala.concurrent.{ExecutionContext, Future}

// Authorizes team-scoped applet actions from the local team projections.
@Singleton
class TeamAccess @Inject() (teamProjectionReadiness: TeamProjectionReadiness, teamProjectionRepository: TeamProjectionRepository)(using
    ExecutionContext
):

  // Validation failures become failed futures, so callers handle every rejection the same way.
  def requireMember(teamId: Long, userId: String): Future[Unit] =
    Future.unit.flatMap { _ =>
      TeamAccess.requireReady(teamProjectionReadiness.membersReady)
      teamProjectionRepository.isActiveMember(teamId, TeamAccess.accountId(userId)).map { active =>
        if !active then throw ExpectedException(FORBIDDEN, "팀 멤버만 수행할 수 있습니다.")
      }
    }

  def requireAppletManager(teamId: Long, userId: String): Future[Unit] =
    requireMember(teamId, userId).flatMap { _ =>
      TeamAccess.requireReady(teamProjectionReadiness.policiesReady)
      val account = TeamAccess.accountId(userId)
      teamProjectionRepository.policiesFor(teamId, account).map { policies =>
        if !TeamAccess.canManageApplets(policies, account) then
          throw ExpectedException(FORBIDDEN, "팀의 앱 관리 권한이 필요합니다.")
      }
    }

object TeamAccess:
  val ManageApplets = "manage_applets"
  val Administrator = "administrator"

  def canManageApplets(policies: Seq[TeamRolePolicy], userId: Long): Boolean =
    TeamRolePolicy.allows(policies, userId, Administrator) || TeamRolePolicy.allows(policies, userId, ManageApplets)

  private def requireReady(ready: Boolean): Unit =
    if !ready then throw ExpectedException(SERVICE_UNAVAILABLE, "팀 권한 정보를 동기화하는 중입니다.")

  // Team events identify users by numeric account IDs. Any other subject cannot be a team member.
  private def accountId(userId: String): Long =
    userId.toLongOption.filter(_ > 0).getOrElse(throw ExpectedException(FORBIDDEN, "팀 멤버만 수행할 수 있습니다."))
