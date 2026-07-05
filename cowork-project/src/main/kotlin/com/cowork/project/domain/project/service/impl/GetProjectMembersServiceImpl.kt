package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.GetProjectMembersService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetProjectMembersServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : GetProjectMembersService {

    override fun getMembers(userId: Long, projectId: Long): List<ProjectMemberResDto> {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)
        return projectMemberRepository.findByProjectId(projectId).map { ProjectMemberResDto.of(it) }
    }
}
