package com.cowork.project.global.consumer

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
) {
    private val log = LoggerFactory.getLogger(ProjectLifecycleHandler::class.java)

    @Transactional
    fun onMemberUpsert(teamId: Long, userId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserId(teamId, userId)
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
        val memberships = teamMembershipRepository.findAllByTeamId(teamId)
        memberships.filter { !it.sourceOccurredAt.toProjectionPrecision().isAfter(version) }
            .forEach { it.markDeleted(version) }
        if (memberships.isNotEmpty()) teamMembershipRepository.saveAll(memberships)

        val projects = projectRepository.findAllByTeamId(teamId)
        if (projects.isEmpty()) {
            log.info("TEAM_DELETED 처리: 대상 프로젝트 없음 [teamId={}]", teamId)
            return
        }
        val members = projectMemberRepository.findAllByProjectIdIn(projects.map { it.id })
        projectRepository.deleteAll(projects)
        members.forEach { projectMemberEventPublisher.publishRemoved(it.projectId, it.userId, version) }
        projects.forEach { projectEventPublisher.publishDeleted(it, version) }
        log.info("TEAM_DELETED 처리 완료 [teamId={}, deletedProjects={}]", teamId, projects.size)
    }

    @Transactional
    fun onMemberRemovedFromTeam(teamId: Long, targetUserId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserId(teamId, targetUserId)
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

        val teamProjectIds = projectRepository.findIdsByTeamId(teamId)
        if (teamProjectIds.isEmpty()) return

        val ownerProjects = projectMemberRepository
            .findAllByUserIdAndRoleAndProjectIdIn(targetUserId, ProjectMemberRole.OWNER, teamProjectIds)
            .map { it.projectId }

        if (ownerProjects.isNotEmpty()) {
            val members = projectMemberRepository.findAllByProjectIdIn(ownerProjects)
            val deletedProjects = projectRepository.findAllById(ownerProjects)
            projectRepository.deleteAllById(ownerProjects)
            members.forEach { projectMemberEventPublisher.publishRemoved(it.projectId, it.userId, version) }
            deletedProjects.forEach { projectEventPublisher.publishDeleted(it, version) }
        }

        val remaining = teamProjectIds - ownerProjects.toSet()
        if (remaining.isNotEmpty()) {
            projectMemberRepository.deleteAllByUserIdAndProjectIdIn(targetUserId, remaining)
            remaining.forEach { projectMemberEventPublisher.publishRemoved(it, targetUserId, version) }
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
    fun onUserDeleted(userId: Long, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val memberships = projectMemberRepository.findAllByUserId(userId)
        val ownerProjectIds = memberships.filter { it.role == ProjectMemberRole.OWNER }.map { it.projectId }

        if (ownerProjectIds.isNotEmpty()) {
            val members = projectMemberRepository.findAllByProjectIdIn(ownerProjectIds)
            val deletedProjects = projectRepository.findAllById(ownerProjectIds)
            projectRepository.deleteAllById(ownerProjectIds)
            members.forEach { projectMemberEventPublisher.publishRemoved(it.projectId, it.userId, version) }
            deletedProjects.forEach { projectEventPublisher.publishDeleted(it, version) }
        }

        val remainingProjectIds = memberships.map { it.projectId } - ownerProjectIds.toSet()
        projectMemberRepository.deleteAllByUserId(userId)
        remainingProjectIds.forEach { projectMemberEventPublisher.publishRemoved(it, userId, version) }

        log.info(
            "USER_DELETED 처리 [userId={}, ownerProjectsDeleted={}]",
            userId,
            ownerProjectIds.size,
        )
    }
}
