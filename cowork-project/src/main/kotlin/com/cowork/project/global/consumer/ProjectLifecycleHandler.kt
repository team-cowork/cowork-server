package com.cowork.project.global.consumer

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.global.support.afterCommit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProjectLifecycleHandler(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
    private val projectMemberEventPublisher: ProjectMemberEventPublisher,
) {
    private val log = LoggerFactory.getLogger(ProjectLifecycleHandler::class.java)

    @Transactional
    fun onMemberInvited(teamId: Long, userIds: List<Long>, role: String) {
        val existing = teamMembershipRepository.findAllByTeamIdAndUserIdIn(teamId, userIds)
            .map { it.userId }.toSet()
        val toSave = userIds.filterNot { it in existing }
            .map { TeamMembership(teamId = teamId, userId = it, role = role) }
        if (toSave.isNotEmpty()) teamMembershipRepository.saveAll(toSave)
        log.info("MEMBER_INVITED 처리 완료 [teamId={}, newMembers={}]", teamId, toSave.size)
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
        val members = projectMemberRepository.findAllByProjectIdIn(projects.map { it.id })
        projectRepository.deleteAll(projects)
        afterCommit {
            members.forEach { projectMemberEventPublisher.publishRemoved(it.projectId, it.userId) }
        }
        log.info("TEAM_DELETED 처리 완료 [teamId={}, deletedProjects={}]", teamId, projects.size)
    }

    @Transactional
    fun onMemberRemovedFromTeam(teamId: Long, targetUserId: Long) {
        teamMembershipRepository.deleteByTeamIdAndUserId(teamId, targetUserId)

        val teamProjectIds = projectRepository.findIdsByTeamId(teamId)
        if (teamProjectIds.isEmpty()) return

        val ownerProjects = projectMemberRepository
            .findAllByUserIdAndRoleAndProjectIdIn(targetUserId, ProjectMemberRole.OWNER, teamProjectIds)
            .map { it.projectId }

        if (ownerProjects.isNotEmpty()) {
            val members = projectMemberRepository.findAllByProjectIdIn(ownerProjects)
            projectRepository.deleteAllById(ownerProjects)
            afterCommit {
                members.forEach { projectMemberEventPublisher.publishRemoved(it.projectId, it.userId) }
            }
        }

        val remaining = teamProjectIds - ownerProjects.toSet()
        if (remaining.isNotEmpty()) {
            projectMemberRepository.deleteAllByUserIdAndProjectIdIn(targetUserId, remaining)
            afterCommit {
                remaining.forEach { projectMemberEventPublisher.publishRemoved(it, targetUserId) }
            }
        }
        log.info(
            "MEMBER_REMOVED 처리 [teamId={}, userId={}, ownerProjectsDeleted={}, membershipsRemoved={}]",
            teamId,
            targetUserId,
            ownerProjects.size,
            remaining.size,
        )
    }

    @Transactional
    fun onUserDeleted(userId: Long) {
        val memberships = projectMemberRepository.findAllByUserId(userId)
        val ownerProjectIds = memberships.filter { it.role == ProjectMemberRole.OWNER }.map { it.projectId }

        if (ownerProjectIds.isNotEmpty()) {
            val members = projectMemberRepository.findAllByProjectIdIn(ownerProjectIds)
            projectRepository.deleteAllById(ownerProjectIds)
            afterCommit {
                members.forEach { projectMemberEventPublisher.publishRemoved(it.projectId, it.userId) }
            }
        }

        val remainingProjectIds = memberships.map { it.projectId } - ownerProjectIds.toSet()
        projectMemberRepository.deleteAllByUserId(userId)
        afterCommit {
            remainingProjectIds.forEach { projectMemberEventPublisher.publishRemoved(it, userId) }
        }

        log.info(
            "USER_DELETED 처리 [userId={}, ownerProjectsDeleted={}]",
            userId,
            ownerProjectIds.size,
        )
    }
}
