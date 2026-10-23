package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.QueryProjectService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryProjectServiceImpl(
    private val teamMembers: TeamMembershipRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : QueryProjectService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): ProjectDetailResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectReader(project, userId)
        val memberCount = teamMembers.findAllByTeamIdAndActiveTrue(project.teamId).count { projectAccessGuard.canRead(project, it.userId) }.toLong()
        return ProjectDetailResDto.of(project, memberCount)
    }
}
