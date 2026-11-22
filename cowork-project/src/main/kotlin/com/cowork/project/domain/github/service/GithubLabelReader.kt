package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.repository.GithubLabelProjectionRepository
import org.springframework.stereotype.Component

@Component
class GithubLabelReader(
    private val githubLabelProjectionRepository: GithubLabelProjectionRepository,
    private val githubSyncMarkers: GithubSyncMarkers,
) {
    fun listLabels(owner: String, repo: String): List<GithubLabelResDto> {
        githubSyncMarkers.requireSynced(GithubSyncStream.LABEL, owner, repo)
        return githubLabelProjectionRepository.findByOwnerAndRepoAndDeletedFalse(owner, repo)
            .map { GithubLabelResDto(name = it.name, color = it.color) }
    }
}
