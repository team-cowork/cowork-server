package com.cowork.project.global.consumer

import com.cowork.project.domain.github.service.ProjectGithubRepoDeletionSupport
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.projection.toProjectionPrecision
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProjectLifecycleHandler(
    private val projectRepository: ProjectRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
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
        repoDeletionSupport.deleteByProjectIds(projects.map { it.id }, version)
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
        val ownedProjects = teamProjects.filter { it.createdBy == targetUserId }
        if (ownedProjects.isNotEmpty()) {
            repoDeletionSupport.deleteByProjectIds(ownedProjects.map { it.id }, version)
            ownedProjects.forEach { projectEventPublisher.publishDeleted(it, version) }
            projectRepository.deleteAll(ownedProjects)
        }
        log.info(
            "MEMBER_REMOVED 처리 [teamId={}, userId={}, ownerProjectsDeleted={}]",
            teamId,
            targetUserId,
            ownedProjects.size,
        )
    }
}
