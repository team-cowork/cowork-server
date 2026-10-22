package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.repository.GithubLabelProjectionRepository
import org.springframework.stereotype.Component

@Component
class GithubLabelReader(
    private val repository: GithubLabelProjectionRepository,
    private val syncMarkers: GithubSyncMarkers,
) {
    fun listLabels(owner: String, repo: String): List<GithubLabelResDto> {
        syncMarkers.requireSynced(GithubSyncStream.LABEL, owner, repo)
        return repository.findByOwnerAndRepoAndDeletedFalse(owner, repo)
            .map { GithubLabelResDto(name = it.name, color = it.color) }
    }
}
