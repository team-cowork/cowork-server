package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.QueryProjectMembersService
import com.cowork.project.domain.project.presentation.data.response.ProjectAccessUserResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryProjectMembersServiceImpl(
    private val teamMembers: TeamMembershipRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : QueryProjectMembersService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): List<ProjectAccessUserResponse> {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectReader(project, userId)
        val members = teamMembers.findAllByTeamIdAndActiveTrue(project.teamId)
        val permissions = projectAccessGuard.permissionsForUsers(project, members)
        return members.filter { permissions[it.userId]?.read == true }
            .map { ProjectAccessUserResponse(projectId, it.userId, permissions.getValue(it.userId).write) }
    }
}
