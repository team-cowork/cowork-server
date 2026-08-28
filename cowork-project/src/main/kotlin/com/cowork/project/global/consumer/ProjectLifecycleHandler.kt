package com.cowork.project.global.consumer

import com.cowork.project.domain.github.service.ProjectGithubRepoDeletionSupport
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.global.projection.toProjectionPrecision
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProjectLifecycleHandler(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
    private val projectMemberEventPublisher: ProjectMemberEventPublisher,
    private val projectEventPublisher: ProjectEventPublisher,
    private val repoDeletionSupport: ProjectGithubRepoDeletionSupport,
) {
    private val log = LoggerFactory.getLogger(ProjectLifecycleHandler::class.java)

    @Transactional
    fun onMemberUpsert(teamId: Long, userId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(teamId, userId)
            ?: TeamMembership(teamId = teamId, userId = userId, role = role, sourceOccurredAt = version)
        val existingVersion = membership.sourceOccurredAt.toProjectionPrecision()
        if (existingVersion.isAfter(version) ||
            (existingVersion == version && !membership.active)
        ) {
            return
        }

        membership.applyUpsert(role, version)
        teamMembershipRepository.save(membership)
        log.info("team.member UPSERT 처리 [teamId={}, userId={}, role={}]", teamId, userId, role)
    }

    @Transactional
    fun onTeamDeleted(teamId: Long, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val memberships = teamMembershipRepository.findAllByTeamIdForUpdate(teamId)
        memberships.filter { !it.sourceOccurredAt.toProjectionPrecision().isAfter(version) }
            .forEach { it.markDeleted(version) }
        if (memberships.isNotEmpty()) teamMembershipRepository.saveAll(memberships)

        val projects = projectRepository.findAllByTeamIdForUpdate(teamId)
        if (projects.isEmpty()) {
            log.info("TEAM_DELETED 처리: 대상 프로젝트 없음 [teamId={}]", teamId)
            return
        }
        val members = projectMemberRepository.findAllByProjectIdInForUpdate(projects.map { it.id })
        repoDeletionSupport.deleteByProjectIds(projects.map { it.id }, version)
        members.forEach { projectMemberEventPublisher.publishRemoved(it, version) }
        projects.forEach { projectEventPublisher.publishDeleted(it, version) }
        projectRepository.deleteAll(projects)
        log.info("TEAM_DELETED 처리 완료 [teamId={}, deletedProjects={}]", teamId, projects.size)
    }

    @Transactional
    fun onMemberRemovedFromTeam(teamId: Long, targetUserId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(teamId, targetUserId)
            ?: TeamMembership(
                teamId = teamId,
                userId = targetUserId,
                role = role,
                active = false,
                sourceOccurredAt = version,
            )
        val existingVersion = membership.sourceOccurredAt.toProjectionPrecision()
        if (existingVersion.isAfter(version) ||
            (existingVersion == version && !membership.active)
        ) {
            return
        }
        membership.markDeleted(version)
        teamMembershipRepository.save(membership)

        val teamProjects = projectRepository.findAllByTeamIdForUpdate(teamId)
        if (teamProjects.isEmpty()) return
        val teamProjectIds = teamProjects.map { it.id }
        val targetMemberships = projectMemberRepository.findAllByUserIdAndProjectIdInForUpdate(
            targetUserId,
            teamProjectIds,
        )
        val ownerProjects = targetMemberships.filter { it.role == ProjectMemberRole.OWNER }.map { it.projectId }

        if (ownerProjects.isNotEmpty()) {
            val members = projectMemberRepository.findAllByProjectIdInForUpdate(ownerProjects)
            val deletedProjects = teamProjects.filter { it.id in ownerProjects }
            repoDeletionSupport.deleteByProjectIds(ownerProjects, version)
            members.forEach { projectMemberEventPublisher.publishRemoved(it, version) }
            deletedProjects.forEach { projectEventPublisher.publishDeleted(it, version) }
            projectRepository.deleteAll(deletedProjects)
        }

        val remaining = teamProjectIds - ownerProjects.toSet()
        if (remaining.isNotEmpty()) {
            val remainingMemberships = targetMemberships.filter { it.projectId in remaining }
                .associateBy { it.projectId }
            remaining.forEach { projectId ->
                val activeMember = remainingMemberships[projectId]
                if (activeMember == null) {
                    projectMemberEventPublisher.publishRemoved(projectId, targetUserId, version)
                } else {
                    projectMemberEventPublisher.publishRemoved(activeMember, version)
                }
            }
            if (remainingMemberships.isNotEmpty()) {
                projectMemberRepository.deleteAll(remainingMemberships.values)
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
}
