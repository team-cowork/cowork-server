package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto

interface ListProjectGithubRepoLinksService {
    fun execute(userId: Long, projectId: Long): List<ProjectGithubRepoResDto>
}
