package com.cowork.project.consumer

import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.domain.TeamMembership
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.project.repository.TeamMembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProjectLifecycleHandler(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
) {
    private val log = LoggerFactory.getLogger(ProjectLifecycleHandler::class.java)

    @Transactional
    fun onMemberInvited(teamId: Long, userIds: List<Long>, role: String) {
        userIds.forEach { userId ->
            if (teamMembershipRepository.findByTeamIdAndUserId(teamId, userId) == null) {
                teamMembershipRepository.save(TeamMembership(teamId = teamId, userId = userId, role = role))
            }
        }
        log.info("MEMBER_INVITED 처리 완료 [teamId={}, userIds={}]", teamId, userIds)
    }

    @Transactional
    fun onRoleChanged(teamId: Long, userId: Long, newRole: String) {
        val membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, userId) ?: return
        membership.role = newRole
        log.info("ROLE_CHANGED 처리 완료 [teamId={}, userId={}, newRole={}]", teamId, userId, newRole)
    }

    @Transactional
    fun onTeamDeleted(teamId: Long) {
        teamMembershipRepository.deleteAllByTeamId(teamId)

        val projects = projectRepository.findAllByTeamId(teamId)
        if (projects.isEmpty()) {
            log.info("TEAM_DELETED 처리: 대상 프로젝트 없음 [teamId={}]", teamId)
            return
        }
        projectRepository.deleteAll(projects)
        log.info("TEAM_DELETED 처리 완료 [teamId={}, deletedProjects={}]", teamId, projects.size)
    }

    @Transactional
    fun onMemberRemovedFromTeam(teamId: Long, targetUserId: Long) {
        teamMembershipRepository.deleteByTeamIdAndUserId(teamId, targetUserId)

        val teamProjectIds = projectRepository.findAllByTeamId(teamId).map { it.id }
        if (teamProjectIds.isEmpty()) return

        val ownerProjects = projectMemberRepository
            .findAllByUserIdAndRoleAndProjectIdIn(targetUserId, ProjectMemberRole.OWNER, teamProjectIds)
            .map { it.projectId }

        if (ownerProjects.isNotEmpty()) {
            projectRepository.deleteAllById(ownerProjects)
        }

        val remaining = teamProjectIds - ownerProjects.toSet()
        if (remaining.isNotEmpty()) {
            projectMemberRepository.deleteAllByUserIdAndProjectIdIn(targetUserId, remaining)
        }
        log.info(
            "MEMBER_REMOVED 처리 [teamId={}, userId={}, ownerProjectsDeleted={}, membershipsRemoved={}]",
            teamId, targetUserId, ownerProjects.size, remaining.size,
        )
    }

    @Transactional
    fun onUserDeleted(userId: Long) {
        val ownerProjectIds = projectMemberRepository
            .findAllByUserIdAndRole(userId, ProjectMemberRole.OWNER)
            .map { it.projectId }

        if (ownerProjectIds.isNotEmpty()) {
            projectRepository.deleteAllById(ownerProjectIds)
        }
        projectMemberRepository.deleteAllByUserId(userId)

        log.info(
            "USER_DELETED 처리 [userId={}, ownerProjectsDeleted={}]",
            userId, ownerProjectIds.size,
        )
    }
}
