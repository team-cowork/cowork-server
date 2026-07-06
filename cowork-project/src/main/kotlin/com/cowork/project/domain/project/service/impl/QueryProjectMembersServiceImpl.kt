package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.QueryProjectMembersService
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryProjectMembersServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : QueryProjectMembersService {

    override fun execute(userId: Long, projectId: Long): List<ProjectMemberResDto> {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)
        return projectMemberRepository.findByProjectId(projectId).map { ProjectMemberResDto.of(it) }
    }
}
