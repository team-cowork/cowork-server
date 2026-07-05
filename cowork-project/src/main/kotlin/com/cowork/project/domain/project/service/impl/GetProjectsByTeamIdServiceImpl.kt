package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.GetProjectsByTeamIdService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetProjectsByTeamIdServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : GetProjectsByTeamIdService {

    override fun getProjectsByTeamId(userId: Long, teamId: Long, pageable: Pageable): Page<ProjectResDto> {
        projectAccessGuard.requireTeamMember(teamId, userId)
        return projectRepository.findByTeamId(teamId, pageable).map { ProjectResDto.of(it) }
    }
}
