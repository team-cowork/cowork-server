package com.cowork.project.domain.github.service

interface ApprovePullRequestService {
    fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int)
}
