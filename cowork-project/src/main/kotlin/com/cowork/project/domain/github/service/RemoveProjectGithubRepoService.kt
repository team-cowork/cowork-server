package com.cowork.project.domain.github.service

interface RemoveProjectGithubRepoService {
    fun execute(userId: Long, projectId: Long, repoId: Long)
}
