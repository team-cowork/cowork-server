package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.QueryProjectService
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryProjectServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : QueryProjectService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): ProjectDetailResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)
        val memberCount = projectMemberRepository.countByProjectId(projectId)
        return ProjectDetailResDto.of(project, memberCount)
    }
}
