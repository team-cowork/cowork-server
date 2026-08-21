package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import org.springframework.data.jpa.repository.JpaRepository

interface ProjectGithubRepoRepository : JpaRepository<ProjectGithubRepo, Long> {
    fun findAllByProjectId(projectId: Long): List<ProjectGithubRepo>
    fun findByIdAndProjectId(id: Long, projectId: Long): ProjectGithubRepo?
    fun existsByTeamIdAndGithubRepoUrl(teamId: Long, githubRepoUrl: String): Boolean
    fun findAllByGithubRepoUrl(githubRepoUrl: String): List<ProjectGithubRepo>
}
