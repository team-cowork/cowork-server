package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.service.ListProjectGithubRepoLinksService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListProjectGithubRepoLinksServiceImpl(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : ListProjectGithubRepoLinksService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): List<ProjectGithubRepoResDto> {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)

        return projectGithubRepoRepository.findAllByProjectId(projectId).map { ProjectGithubRepoResDto.of(it) }
    }
}
