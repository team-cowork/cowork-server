package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.repository.GithubLabelProjectionRepository
import org.springframework.stereotype.Component

@Component
class GithubLabelReader(
    private val repository: GithubLabelProjectionRepository,
    private val githubAppClient: GithubAppClient,
    private val callExecutor: GithubAppCallExecutor,
) {
    fun listLabels(owner: String, repo: String): List<GithubLabelResDto> {
        val projections = repository.findByOwnerAndRepoAndDeletedFalse(owner, repo)
        if (projections.isEmpty()) {
            return callExecutor.execute { githubAppClient.listLabels(owner, repo) }
        }

        return projections.map { GithubLabelResDto(name = it.name, color = it.color) }
    }
}
