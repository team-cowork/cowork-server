package com.cowork.project.domain.github.service

interface MergePullRequestService {
    fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int)
}
